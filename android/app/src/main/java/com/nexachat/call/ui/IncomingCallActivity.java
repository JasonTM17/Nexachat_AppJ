package com.nexachat.call.ui;

import android.annotation.SuppressLint;
import android.Manifest;
import androidx.activity.ComponentActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.nexachat.R;
import com.nexachat.call.CallLauncher;
import com.nexachat.call.core.CallError;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallMediaType;
import com.nexachat.call.core.IncomingCallPushAdmission;
import com.nexachat.call.core.IncomingCallPushData;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.media.CallMediaEngineProvider;
import com.nexachat.call.media.CallRingtonePlayer;
import com.nexachat.call.service.CallForegroundService;
import com.nexachat.call.service.CallNotifications;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.ui.MessageUiRules;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.inject.Inject;

/** Lock-screen incoming call view; call state remains owned by the process CallManager. */
@AndroidEntryPoint
public class IncomingCallActivity extends ComponentActivity {
    public static final String EXTRA_CALL_ID = "com.nexachat.call.CALL_ID";
    public static final String EXTRA_SERVER_TS = "com.nexachat.call.SERVER_TS";
    public static final String EXTRA_RECIPIENT_ID = "com.nexachat.call.RECIPIENT_ID";
    public static final String EXTRA_RING_EXPIRES_AT = "com.nexachat.call.RING_EXPIRES_AT";
    public static final String EXTRA_SESSION_EPOCH = "com.nexachat.call.SESSION_EPOCH";

    private static final int REQUEST_MICROPHONE = 7220;
    private static final int REQUEST_NOTIFICATIONS = 7221;
    private static final int REQUEST_CAMERA = 7222;
    private static final String PREFS = "call_permissions";
    private static final String PREF_CAMERA_REQUESTED = "call.cameraRequested";
    private static final String STATE_PENDING_ACCEPT = "call.pendingAccept";
    private static final String STATE_ACCEPT_AS_VOICE = "call.acceptAsVoice";
    private static final long ENDED_SCREEN_MS = 1600L;
    private static final Logger LOGGER = Logger.getLogger("IncomingCallActivity");

    @Inject CallManager callManager;
    @Inject IncomingCallPushAdmission incomingCallPushAdmission;

    private TextView initials;
    private TextView peerName;
    private TextView status;
    private Button acceptButton;
    private Button declineButton;
    private View pulseRing;
    private android.animation.AnimatorSet pulseAnimator;
    private CallSession currentSession;
    private CallManager.CallListener listener;
    private CallRingtonePlayer ringtonePlayer;
    private boolean pendingAccept;
    private boolean acceptAsVoiceOnly;
    private boolean resolving;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable finishEndedScreen = this::finish;
    private Disposable fallbackAdmission;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase));
    }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingAccept = savedInstanceState != null
                && IncomingCallPermissionState.pendingAcceptAfterRecreation(
                        savedInstanceState.getBoolean(STATE_PENDING_ACCEPT, false));
        acceptAsVoiceOnly = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ACCEPT_AS_VOICE, false);
        setShowOnLockScreen();
        setContentView(R.layout.activity_incoming_call);
        initials = findViewById(R.id.incoming_initials);
        peerName = findViewById(R.id.incoming_peer_name);
        status = findViewById(R.id.incoming_status);
        acceptButton = findViewById(R.id.incoming_accept);
        declineButton = findViewById(R.id.incoming_decline);
        pulseRing = findViewById(R.id.incoming_pulse_ring);
        callManager.attachMediaEngine(CallMediaEngineProvider.get(getApplicationContext()));
        acceptButton.setOnClickListener(view -> requestAccept());
        declineButton.setOnClickListener(view -> decline());
        currentSession = callManager.currentSessionSnapshot();
        IncomingCallGate.Decision gate = gateFor(currentSession);
        if (gate == IncomingCallGate.Decision.NO_ACTIVE_SESSION) {
            if (beginFallbackAdmission()) return;
            finishThroughGate(gate);
            return;
        }
        if (gate != IncomingCallGate.Decision.ALL_CLEAR) {
            finishThroughGate(gate);
            return;
        }
        ringtonePlayer = new CallRingtonePlayer(getApplicationContext());
        render(currentSession);
        if (savedInstanceState == null
                && CallNotificationActionRouter.ACTION_ACCEPT.equals(getIntent().getAction())) {
            requestAccept();
        } else if (CallNotificationActionRouter.ACTION_DECLINE.equals(getIntent().getAction())) {
            decline();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        listener = new CallManager.CallListener() {
            @Override public void onCallChanged(CallSession session) {
                if (session != null
                        && callManager.hasIncomingPushAdmission(session.getCallId())
                        && !callManager.isIncomingPushAdmissionCurrent(session.getCallId())) {
                    currentSession = session;
                    finishThroughGate(IncomingCallGate.Decision.STALE_PUSH_SESSION);
                    return;
                }
                if (session == null) {
                    currentSession = null;
                    finishThroughGate(IncomingCallGate.Decision.NO_ACTIVE_SESSION, null);
                    return;
                }
                if (!matchesTargetCall(session)) {
                    currentSession = session;
                    finishThroughGate(IncomingCallGate.Decision.TARGET_CALL_MISMATCH);
                    return;
                }
                currentSession = session;
                render(session);
                boolean isRinging = session.getState() == CallState.INCOMING_RINGING
                        || session.getState() == CallState.PENDING_INCOMING;
                if (!isRinging) {
                    stopRingtone();
                    CallNotifications.clearIncoming(IncomingCallActivity.this);
                    if (session.getState() == CallState.IDLE) {
                        finishThroughGate(IncomingCallGate.Decision.NO_ACTIVE_SESSION, session);
                    } else if (session.getState() == CallState.ENDED && !isFinishing()) {
                        resolving = true;
                        mainHandler.removeCallbacks(finishEndedScreen);
                        mainHandler.postDelayed(finishEndedScreen, ENDED_SCREEN_MS);
                    }
                } else {
                    ringtone().start();
                }
            }

            @Override public void onCallError(CallError error) {
                Toast.makeText(IncomingCallActivity.this, R.string.call_state_failed,
                        Toast.LENGTH_LONG).show();
            }
        };
        callManager.addListener(listener);
        currentSession = callManager.currentSessionSnapshot();
        IncomingCallGate.Decision gate = IncomingCallGate.decideOnStart(
                currentSession,
                matchesTargetCall(currentSession),
                hasAdmission(currentSession),
                pushIntentCurrent(currentSession),
                isCurrentPushSession(currentSession));
        if (!resolving && gate != IncomingCallGate.Decision.ALL_CLEAR) {
            finishThroughGate(gate);
            return;
        }
        render(currentSession);
        if (!resolving && matchesTargetCall(currentSession)
                && isCurrentPushSession(currentSession)
                && (currentSession.getState() == CallState.INCOMING_RINGING
                    || currentSession.getState() == CallState.PENDING_INCOMING)) {
            CallNotifications.clearIncoming(this);
            ringtone().start();
        }
    }

    @Override protected void onStop() {
        if (listener != null) {
            callManager.removeListener(listener);
            listener = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (!resolving && matchesTargetCall(currentSession)
                && (currentSession.getState() == CallState.INCOMING_RINGING
                    || currentSession.getState() == CallState.PENDING_INCOMING)) {
            stopRingtone();
            if (isCurrentPushSession(currentSession)) {
                CallNotifications.showIncoming(this, currentSession);
            } else {
                CallNotifications.clearIncoming(this);
            }
        }
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PENDING_ACCEPT, pendingAccept);
        outState.putBoolean(STATE_ACCEPT_AS_VOICE, acceptAsVoiceOnly);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        currentSession = callManager.currentSessionSnapshot();
        IncomingCallGate.Decision gate = gateFor(currentSession);
        if (gate != IncomingCallGate.Decision.ALL_CLEAR
                && gate != IncomingCallGate.Decision.NOT_RINGING) {
            finishThroughGate(gate);
            return;
        }
        render(currentSession);
        if (CallNotificationActionRouter.ACTION_ACCEPT.equals(intent.getAction())) {
            requestAccept();
        } else if (CallNotificationActionRouter.ACTION_DECLINE.equals(intent.getAction())) {
            decline();
        }
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacks(finishEndedScreen);
        if (fallbackAdmission != null) {
            fallbackAdmission.dispose();
            fallbackAdmission = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        stopRingtone();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (granted(grantResults)) requestCameraThenNotifications();
            else denyAccept();
        } else if (requestCode == REQUEST_CAMERA) {
            if (granted(grantResults)) {
                requestNotificationPermissionThenContinue();
            } else {
                boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.CAMERA);
                showCameraFallbackDialog(!rationale);
            }
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            if (!granted(grantResults)) {
                Toast.makeText(this, R.string.call_notifications_permission_needed,
                        Toast.LENGTH_LONG).show();
            }
            continueAccept();
        }
    }

    private void render(CallSession session) {
        if (session == null) return;
        String name = session.getPeerDisplayName();
        if (name == null || name.trim().isEmpty()) name = getString(R.string.call_peer_fallback);
        peerName.setText(name);
        initials.setText(MessageUiRules.extractInitials(name));
        CallAvatarColorResolver.apply(this, initials, name);
        status.setText(CallUiModelMapper.map(session).getTitleRes());
        status.setContentDescription(status.getText());
        if (acceptButton instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) acceptButton)
                    .setIconResource(session.getMediaType() == CallMediaType.VIDEO
                            ? R.drawable.ic_video_call : R.drawable.ic_call);
        }
        boolean ringing = session.getState() == CallState.INCOMING_RINGING
                || session.getState() == CallState.PENDING_INCOMING;
        updatePulseAnimation(ringing);
        acceptButton.setEnabled(ringing);
        declineButton.setEnabled(ringing);
    }

    private void updatePulseAnimation(boolean isLive) {
        if (pulseRing == null) return;
        float scale = 1.0f;
        try {
            scale = android.provider.Settings.Global.getFloat(
                    getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f);
        } catch (Exception ignored) { }
        boolean enabled = android.animation.ValueAnimator.areAnimatorsEnabled();
        boolean shouldAnimate = CallPulseAnimationPolicy.shouldAnimate(scale, enabled, isLive);

        if (shouldAnimate) {
            if (pulseAnimator == null || !pulseAnimator.isStarted()) {
                android.animation.ObjectAnimator scaleX =
                        android.animation.ObjectAnimator.ofFloat(pulseRing, "scaleX", 0.95f, 1.18f);
                android.animation.ObjectAnimator scaleY =
                        android.animation.ObjectAnimator.ofFloat(pulseRing, "scaleY", 0.95f, 1.18f);
                android.animation.ObjectAnimator alpha =
                        android.animation.ObjectAnimator.ofFloat(pulseRing, "alpha", 0.45f, 0.10f);
                scaleX.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                scaleX.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                scaleY.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                scaleY.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                alpha.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                alpha.setRepeatMode(android.animation.ValueAnimator.REVERSE);

                pulseAnimator = new android.animation.AnimatorSet();
                pulseAnimator.playTogether(scaleX, scaleY, alpha);
                pulseAnimator.setDuration(1600L);
                pulseAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                pulseAnimator.start();
                pulseRing.setVisibility(android.view.View.VISIBLE);
            }
        } else {
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            pulseRing.setScaleX(1.0f);
            pulseRing.setScaleY(1.0f);
            pulseRing.setAlpha(0.25f);
            pulseRing.setVisibility(isLive ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    private void requestAccept() {
        if (!IncomingCallPermissionState.canStartAccept(
                pendingAccept,
                currentSession != null
                        && (currentSession.getState() == CallState.INCOMING_RINGING
                            || currentSession.getState() == CallState.PENDING_INCOMING))) return;
        pendingAccept = true;
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.RECORD_AUDIO);
        CallPermissionHelper.Decision decision = CallPermissionHelper.microphoneDecision(
                granted, rationale);
        if (decision == CallPermissionHelper.Decision.GRANTED) {
            requestCameraThenNotifications();
        } else if (decision == CallPermissionHelper.Decision.SHOW_RATIONALE) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.call_mic_rationale)
                    .setPositiveButton(R.string.call_permission_continue,
                            (dialog, which) -> requestMicrophonePermission())
                    .setNegativeButton(R.string.call_permission_cancel,
                            (dialog, which) -> denyAccept())
                    .show();
        } else {
            requestMicrophonePermission();
        }
    }

    private void requestMicrophonePermission() {
        ActivityCompat.requestPermissions(
                this, new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
    }

    /** Video offers also need the camera; a denied camera answers as a voice call. */
    private void requestCameraThenNotifications() {
        boolean videoOffer = currentSession != null
                && currentSession.getMediaType() == CallMediaType.VIDEO;
        boolean hasCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        if (!videoOffer || !hasCamera) {
            requestNotificationPermissionThenContinue();
            return;
        }
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.CAMERA);
        CallPermissionHelper.Decision decision = CallPermissionHelper.cameraDecision(
                granted, rationale, cameraRequestedBefore());
        if (decision == CallPermissionHelper.Decision.GRANTED) {
            requestNotificationPermissionThenContinue();
        } else if (decision == CallPermissionHelper.Decision.SHOW_RATIONALE) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.call_camera_rationale)
                    .setPositiveButton(R.string.call_permission_continue,
                            (dialog, which) -> requestCameraPermission())
                    .setNegativeButton(R.string.call_permission_cancel,
                            (dialog, which) -> denyAccept())
                    .show();
        } else if (decision == CallPermissionHelper.Decision.OPEN_SETTINGS) {
            showCameraFallbackDialog(true);
        } else {
            requestCameraPermission();
        }
    }

    private void requestCameraPermission() {
        markCameraRequested();
        ActivityCompat.requestPermissions(
                this, new String[] {Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    private void showCameraFallbackDialog(boolean permanentDeny) {
        if (isFinishing()) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.call_camera_permission_needed)
                .setPositiveButton(R.string.call_answer_as_voice, (dialog, which) -> {
                    acceptAsVoiceOnly = true;
                    requestNotificationPermissionThenContinue();
                })
                .setNegativeButton(R.string.call_permission_cancel,
                        (dialog, which) -> denyAccept());
        if (permanentDeny) {
            builder.setNeutralButton(R.string.call_open_settings,
                    (dialog, which) -> openAppSettings());
        }
        builder.show();
    }

    private boolean cameraRequestedBefore() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_CAMERA_REQUESTED, false);
    }

    private void markCameraRequested() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(PREF_CAMERA_REQUESTED, true).apply();
    }

    private void openAppSettings() {
        Intent settings = new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException ignored) { }
    }

    @SuppressLint("InlinedApi")
    private void requestNotificationPermissionThenContinue() {
        boolean granted = Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
        boolean rationale = Build.VERSION.SDK_INT >= 33
                && ActivityCompat.shouldShowRequestPermissionRationale(
                        this, Manifest.permission.POST_NOTIFICATIONS);
        CallPermissionHelper.Decision decision = CallPermissionHelper.notificationDecision(
                Build.VERSION.SDK_INT, granted, rationale);
        if (decision == CallPermissionHelper.Decision.REQUEST) {
            ActivityCompat.requestPermissions(
                    this, new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else if (decision == CallPermissionHelper.Decision.SHOW_RATIONALE) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.call_notifications_rationale)
                    .setPositiveButton(R.string.call_permission_continue,
                            (dialog, which) -> ActivityCompat.requestPermissions(
                                     this, new String[] {Manifest.permission.POST_NOTIFICATIONS},
                                    REQUEST_NOTIFICATIONS))
                    .setNegativeButton(R.string.call_permission_cancel, (dialog, which) -> {
                        Toast.makeText(this, R.string.call_notifications_permission_needed,
                                Toast.LENGTH_LONG).show();
                        continueAccept();
                    })
                    .show();
        } else {
            continueAccept();
        }
    }

    private void continueAccept() {
        pendingAccept = false;
        if (!matchesTargetCall(currentSession)
                || !isCurrentPushSession(currentSession)
                || (currentSession.getState() != CallState.INCOMING_RINGING
                    && currentSession.getState() != CallState.PENDING_INCOMING)) return;
        resolving = true;
        stopRingtone();
        CallNotifications.clearIncoming(this);
        CallForegroundService.startForCall(this,
                !acceptAsVoiceOnly && currentSession.getMediaType() == CallMediaType.VIDEO);
        if (acceptAsVoiceOnly && currentSession.getMediaType() == CallMediaType.VIDEO) {
            callManager.acceptAsVoice(currentSession.getCallId());
        } else {
            callManager.accept(currentSession.getCallId());
        }
        CallLauncher.start(this,
                currentSession.getConversationId(),
                currentSession.getPeerUserId(),
                currentSession.getPeerDisplayName(),
                getIntent().getStringExtra(CallLauncher.EXTRA_PEER_PHONE),
                acceptAsVoiceOnly ? CallMediaType.AUDIO : currentSession.getMediaType());
        finish();
    }

    private void denyAccept() {
        pendingAccept = false;
        resolving = true;
        if (currentSession != null) callManager.reject(currentSession.getCallId());
        stopRingtone();
        CallNotifications.clearIncoming(this);
        showPermissionFallback();
    }

    private void decline() {
        resolving = true;
        if (matchesTargetCall(currentSession)
                && (currentSession.getState() == CallState.INCOMING_RINGING
                    || currentSession.getState() == CallState.PENDING_INCOMING)) {
            callManager.reject(currentSession.getCallId());
        }
        stopRingtone();
        CallNotifications.clearIncoming(this);
        finish();
    }

    private void showPermissionFallback() {
        String phone = getIntent().getStringExtra(CallLauncher.EXTRA_PEER_PHONE);
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.call_mic_permission_needed)
                .setNegativeButton(android.R.string.ok, (ignored, which) -> finish());
        if (CallPermissionHelper.hasPhoneFallback(phone)) {
            dialog.setPositiveButton(R.string.call_via_phone, (ignored, which) -> dialPhone(phone));
        }
        dialog.show();
    }

    private void dialPhone(String phoneNumber) {
        if (!CallPermissionHelper.hasPhoneFallback(phoneNumber)) return;
        Intent dial = new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phoneNumber, null));
        try {
            startActivity(dial);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.call_phone_unavailable, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void setShowOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private static boolean granted(int[] grantResults) {
        return grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }

    private IncomingCallGate.Decision gateFor(@Nullable CallSession session) {
        return IncomingCallGate.decide(
                session,
                matchesTargetCall(session),
                hasAdmission(session),
                pushIntentCurrent(session),
                isCurrentPushSession(session));
    }

    private boolean hasAdmission(@Nullable CallSession session) {
        return session != null && callManager.hasIncomingPushAdmission(session.getCallId());
    }

    private boolean pushIntentCurrent(@Nullable CallSession session) {
        return session != null && isCurrentPushIntent(session.getCallId());
    }

    /** Logs the gate, keeps a still-ringing call visible through the notification, and finishes. */
    private void finishThroughGate(IncomingCallGate.Decision gate) {
        finishThroughGate(gate, currentSession);
    }

    private void finishThroughGate(IncomingCallGate.Decision gate, @Nullable CallSession session) {
        resolving = true;
        stopRingtone();
        logGate(gate, session);
        boolean ringing = session != null
                && (session.getState() == CallState.INCOMING_RINGING
                    || session.getState() == CallState.PENDING_INCOMING);
        if (IncomingCallGate.shouldPostIncomingNotification(ringing, isCurrentPushSession(session))) {
            CallNotifications.showIncomingFromGate(this, session,
                    callManager.currentIncomingPushData(session.getCallId()));
        } else {
            CallNotifications.clearIncoming(this);
        }
        finish();
    }

    private void logGate(IncomingCallGate.Decision gate, @Nullable CallSession session) {
        String callId = session == null ? null : session.getCallId();
        String prefix = callId == null ? "none" : callId.substring(0, Math.min(8, callId.length()));
        LOGGER.info("incoming-call-gate=" + gate + " callId=" + prefix);
    }

    private CallRingtonePlayer ringtone() {
        if (ringtonePlayer == null) ringtonePlayer = new CallRingtonePlayer(getApplicationContext());
        return ringtonePlayer;
    }

    private void stopRingtone() {
        if (ringtonePlayer != null) ringtonePlayer.stop();
    }

    private boolean matchesTargetCall(CallSession session) {
        if (session == null) return false;
        String requestedCallId = getIntent().getStringExtra(EXTRA_CALL_ID);
        return requestedCallId == null || requestedCallId.equals(session.getCallId());
    }

    private boolean beginFallbackAdmission() {
        IncomingCallPushData pushData = IncomingCallIntentCodec.readPushData(getIntent());
        if (pushData == null || !pushData.isValidAt(System.currentTimeMillis())) {
            return false;
        }

        resolving = true;
        fallbackAdmission = incomingCallPushAdmission.admit(pushData)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(session -> {
                    AtomicBoolean restored = new AtomicBoolean();
                    boolean current = incomingCallPushAdmission.runIfCurrent(pushData, () -> {
                        currentSession = session;
                        resolving = false;
                        render(session);
                        if (!isFinishing()) ringtone().start();
                        restored.set(true);
                    });
                    if (!current || !restored.get()) {
                        dismissAfterFailedAdmission();
                        return;
                    }
                    if (CallNotificationActionRouter.ACTION_ACCEPT.equals(getIntent().getAction())) {
                        requestAccept();
                    } else if (CallNotificationActionRouter.ACTION_DECLINE.equals(
                            getIntent().getAction())) {
                        decline();
                    }
                }, ignored -> dismissAfterFailedAdmission(), this::dismissAfterFailedAdmission);
        return true;
    }

    private void dismissAfterFailedAdmission() {
        finishThroughGate(IncomingCallGate.Decision.PUSH_ADMISSION_FAILED);
    }

    private boolean isCurrentPushSession(CallSession session) {
        return session != null
                && (!callManager.hasIncomingPushAdmission(session.getCallId())
                    || callManager.isIncomingPushAdmissionCurrent(session.getCallId()));
    }

    private boolean isCurrentPushIntent(String callId) {
        IncomingCallPushData pushData = IncomingCallIntentCodec.readPushData(getIntent());
        if (pushData == null) return callManager.isIncomingPushAdmissionCurrent(callId);
        return callId.equals(pushData.getCallId())
                && incomingCallPushAdmission.runIfCurrent(pushData, () -> { });
    }

}
