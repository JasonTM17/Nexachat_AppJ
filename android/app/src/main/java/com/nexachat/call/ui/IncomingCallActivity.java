package com.nexachat.call.ui;

import android.annotation.SuppressLint;
import android.Manifest;
import androidx.activity.ComponentActivity;
import android.app.AlertDialog;
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
    private static final String STATE_PENDING_ACCEPT = "call.pendingAccept";
    private static final long ENDED_SCREEN_MS = 1600L;

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
        setShowOnLockScreen();
        setContentView(R.layout.activity_incoming_call);
        initials = findViewById(R.id.incoming_initials);
        peerName = findViewById(R.id.incoming_peer_name);
        status = findViewById(R.id.incoming_status);
        acceptButton = findViewById(R.id.incoming_accept);
        declineButton = findViewById(R.id.incoming_decline);
        pulseRing = findViewById(R.id.incoming_pulse_ring);
        ringtonePlayer = new CallRingtonePlayer(getApplicationContext());
        callManager.attachMediaEngine(CallMediaEngineProvider.get(getApplicationContext()));
        acceptButton.setOnClickListener(view -> requestAccept());
        declineButton.setOnClickListener(view -> decline());
        currentSession = callManager.currentSessionSnapshot();
        if (currentSession == null || currentSession.getState() == CallState.IDLE) {
            if (beginFallbackAdmission()) return;
            CallNotifications.clearIncoming(this);
            finish();
            return;
        }
        if (callManager.hasIncomingPushAdmission(currentSession.getCallId())
                && !isCurrentPushIntent(currentSession.getCallId())) {
            resolving = true;
            CallNotifications.clearIncoming(this);
            finish();
            return;
        }
        if (!matchesTargetCall(currentSession)) {
            CallNotifications.clearIncoming(this);
            finish();
            return;
        }
        render(currentSession);
        if (currentSession == null
                || (currentSession.getState() != CallState.INCOMING_RINGING
                    && currentSession.getState() != CallState.PENDING_INCOMING)) {
            finish();
            return;
        }
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
                    resolving = true;
                    ringtonePlayer.stop();
                    finish();
                    return;
                }
                if (!matchesTargetCall(session)) {
                    resolving = true;
                    ringtonePlayer.stop();
                    finish();
                    return;
                }
                currentSession = session;
                render(session);
                boolean isRinging = session != null
                        && (session.getState() == CallState.INCOMING_RINGING
                            || session.getState() == CallState.PENDING_INCOMING);
                if (!isRinging) {
                    ringtonePlayer.stop();
                    CallNotifications.clearIncoming(IncomingCallActivity.this);
                    if (session == null) {
                        resolving = true;
                        finish();
                    } else if (session.getState() == CallState.ENDED && !isFinishing()) {
                        resolving = true;
                        mainHandler.removeCallbacks(finishEndedScreen);
                        mainHandler.postDelayed(finishEndedScreen, ENDED_SCREEN_MS);
                    }
                } else {
                    ringtonePlayer.start();
                }
            }

            @Override public void onCallError(CallError error) {
                Toast.makeText(IncomingCallActivity.this, R.string.call_state_failed,
                        Toast.LENGTH_LONG).show();
            }
        };
        callManager.addListener(listener);
        currentSession = callManager.currentSessionSnapshot();
        if (!resolving && (!matchesTargetCall(currentSession)
                || !isCurrentPushSession(currentSession)
                || (callManager.hasIncomingPushAdmission(currentSession.getCallId())
                    && !isCurrentPushIntent(currentSession.getCallId())))) {
            resolving = true;
            ringtonePlayer.stop();
            CallNotifications.clearIncoming(this);
            finish();
            return;
        }
        render(currentSession);
        if (!resolving && matchesTargetCall(currentSession)
                && isCurrentPushSession(currentSession)
                && (currentSession.getState() == CallState.INCOMING_RINGING
                    || currentSession.getState() == CallState.PENDING_INCOMING)) {
            ringtonePlayer.start();
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
            ringtonePlayer.stop();
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
        super.onSaveInstanceState(outState);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        currentSession = callManager.currentSessionSnapshot();
        if (!matchesTargetCall(currentSession)
                || (currentSession != null
                    && callManager.hasIncomingPushAdmission(currentSession.getCallId())
                    && !isCurrentPushIntent(currentSession.getCallId()))) {
            resolving = true;
            ringtonePlayer.stop();
            CallNotifications.clearIncoming(this);
            finish();
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
        if (ringtonePlayer != null) ringtonePlayer.stop();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (granted(grantResults)) requestNotificationPermissionThenContinue();
            else denyAccept();
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
            requestNotificationPermissionThenContinue();
        } else if (decision == CallPermissionHelper.Decision.SHOW_RATIONALE) {
            new AlertDialog.Builder(this)
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
            new AlertDialog.Builder(this)
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
        ringtonePlayer.stop();
        CallNotifications.clearIncoming(this);
        CallForegroundService.startForCall(this);
        callManager.accept(currentSession.getCallId());
        CallLauncher.start(this,
                currentSession.getConversationId(),
                currentSession.getPeerUserId(),
                currentSession.getPeerDisplayName(),
                getIntent().getStringExtra(CallLauncher.EXTRA_PEER_PHONE));
        finish();
    }

    private void denyAccept() {
        pendingAccept = false;
        resolving = true;
        if (currentSession != null) callManager.reject(currentSession.getCallId());
        ringtonePlayer.stop();
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
        ringtonePlayer.stop();
        CallNotifications.clearIncoming(this);
        finish();
    }

    private void showPermissionFallback() {
        String phone = getIntent().getStringExtra(CallLauncher.EXTRA_PEER_PHONE);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
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
                        if (!isFinishing()) ringtonePlayer.start();
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
        resolving = true;
        if (ringtonePlayer != null) ringtonePlayer.stop();
        CallNotifications.clearIncoming(this);
        finish();
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
