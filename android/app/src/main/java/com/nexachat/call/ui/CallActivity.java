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
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.nexachat.R;
import com.nexachat.call.CallLauncher;
import com.nexachat.call.core.CallEndReason;
import com.nexachat.call.core.CallError;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallMediaEngine;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.media.CallMediaEngineProvider;
import com.nexachat.call.service.CallForegroundService;
import com.nexachat.call.ui.CallPermissionHelper.Decision;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.ui.MessageUiRules;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/** Classic XML call screen; the singleton manager owns state across rotation. */
@AndroidEntryPoint
public class CallActivity extends ComponentActivity {
    private static final int REQUEST_MICROPHONE = 7210;
    private static final int REQUEST_NOTIFICATIONS = 7211;
    private static final long ENDED_SCREEN_MS = 1600L;
    private static final String STATE_PENDING_PERMISSION = "call.pendingPermission";
    private static final String STATE_START_REQUESTED = "call.startRequested";

    private enum PendingOperation { NONE, START_OUTGOING, ACCEPT_INCOMING }

    @Inject CallManager callManager;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (currentSession != null && currentSession.getState() == CallState.ACTIVE) {
                renderTimer();
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };
    private final Runnable finishEndedScreen = this::finish;

    private TextView peerName;
    private TextView peerInitials;
    private TextView status;
    private TextView timer;
    private Button muteButton;
    private Button speakerButton;
    private Button hangUpButton;
    private CallSession currentSession;
    private CallManager.CallListener listener;
    private PendingOperation pendingOperation = PendingOperation.NONE;
    private boolean startRequested;
    private boolean callErrorDialogShown;
    private PowerManager.WakeLock proximityWakeLock;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.INSTANCE.wrapContext(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);
        bindViews();
        if (savedInstanceState != null) {
            startRequested = savedInstanceState.getBoolean(STATE_START_REQUESTED, false);
            pendingOperation = PendingOperation.values()[Math.max(0, Math.min(
                    PendingOperation.values().length - 1,
                    savedInstanceState.getInt(STATE_PENDING_PERMISSION, 0)))];
        }
        callManager.attachMediaEngine(CallMediaEngineProvider.get(getApplicationContext()));
        currentSession = callManager.session().getValue();
        render(currentSession);
        if (isInProgress(currentSession)) {
            startRequested = true;
        } else if (!startRequested && hasOutgoingIntent()) {
            requestOperation(PendingOperation.START_OUTGOING);
        }
        bindActions();
        updateProximityWakeLock();
    }

    @Override protected void onStart() {
        super.onStart();
        listener = new CallManager.CallListener() {
            @Override public void onCallChanged(CallSession session) {
                currentSession = session;
                render(session);
                updateProximityWakeLock();
                if (session != null && session.getState() == CallState.ENDED) {
                    mainHandler.removeCallbacks(finishEndedScreen);
                    mainHandler.postDelayed(finishEndedScreen, ENDED_SCREEN_MS);
                }
            }

            @Override public void onCallError(CallError error) {
                showCallError(error);
            }
        };
        callManager.addListener(listener);
        currentSession = callManager.session().getValue();
        render(currentSession);
    }

    @Override protected void onResume() {
        super.onResume();
        updateProximityWakeLock();
    }

    @Override protected void onStop() {
        if (listener != null) {
            callManager.removeListener(listener);
            listener = null;
        }
        releaseProximityWakeLock();
        super.onStop();
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacks(timerTick);
        mainHandler.removeCallbacks(finishEndedScreen);
        releaseProximityWakeLock();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_START_REQUESTED, startRequested);
        outState.putInt(STATE_PENDING_PERMISSION, pendingOperation.ordinal());
        super.onSaveInstanceState(outState);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (granted(grantResults)) {
                requestNotificationPermissionThenContinue();
            } else {
                denyPendingOperation();
            }
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            if (!granted(grantResults)) {
                Toast.makeText(this, R.string.call_notifications_permission_needed,
                        Toast.LENGTH_LONG).show();
            }
            continuePendingOperation();
        }
    }

    private void bindViews() {
        peerName = findViewById(R.id.call_peer_name);
        peerInitials = findViewById(R.id.call_peer_initials);
        status = findViewById(R.id.call_status);
        timer = findViewById(R.id.call_timer);
        muteButton = findViewById(R.id.call_mute);
        speakerButton = findViewById(R.id.call_speaker);
        hangUpButton = findViewById(R.id.call_hang_up);
    }

    private void bindActions() {
        muteButton.setOnClickListener(view -> {
            if (currentSession != null) callManager.setMuted(!currentSession.isMuted());
        });
        speakerButton.setOnClickListener(view -> {
            if (currentSession != null) callManager.setSpeaker(!currentSession.isSpeakerOn());
        });
        hangUpButton.setOnClickListener(view -> callManager.hangUp());
    }

    private void render(CallSession session) {
        if (peerName == null) return;
        if (session == null || session.getState() == CallState.IDLE) {
            String requestedName = getIntent().getStringExtra(CallLauncher.EXTRA_PEER_NAME);
            if (requestedName == null || requestedName.trim().isEmpty()) {
                requestedName = getString(R.string.call_peer_fallback);
            }
            peerName.setText(requestedName);
            peerInitials.setText(MessageUiRules.extractInitials(
                    peerName.getText() == null ? null : peerName.getText().toString()));
            status.setText(R.string.call_state_calling);
            status.setContentDescription(status.getText());
            return;
        }
        currentSession = session;
        String name = session.getPeerDisplayName();
        if (name == null || name.trim().isEmpty()) name = getString(R.string.call_peer_fallback);
        peerName.setText(name);
        peerInitials.setText(MessageUiRules.extractInitials(name));
        CallAvatarColorResolver.apply(this, peerInitials, name);
        CallUiModel model = CallUiModelMapper.map(session);
        status.setText(model.getTitleRes());
        if (model.getSubtitleRes() != 0) status.setText(model.getSubtitleRes());
        status.setContentDescription(status.getText());
        if (model.getTitleRes() != lastAnnouncedStatus) {
            status.announceForAccessibility(getString(model.getTitleRes()));
            lastAnnouncedStatus = model.getTitleRes();
        }
        timer.setVisibility(model.isShowTimer() ? View.VISIBLE : View.GONE);
        if (model.isShowTimer()) {
            renderTimer();
            mainHandler.removeCallbacks(timerTick);
            mainHandler.post(timerTick);
        } else {
            mainHandler.removeCallbacks(timerTick);
        }
        muteButton.setVisibility(model.isShowMute() ? View.VISIBLE : View.GONE);
        speakerButton.setVisibility(model.isShowSpeaker() ? View.VISIBLE : View.GONE);
        hangUpButton.setVisibility(model.isShowHangUp() ? View.VISIBLE : View.GONE);
        if (model.getHangUpLabelRes() != 0) {
            hangUpButton.setText(model.getHangUpLabelRes());
            hangUpButton.setContentDescription(getString(model.getHangUpLabelRes()));
        }
        muteButton.setText(session.isMuted() ? R.string.call_unmute : R.string.call_mute);
        muteButton.setContentDescription(getString(
                session.isMuted() ? R.string.call_unmute : R.string.call_mute));
        speakerButton.setText(session.isSpeakerOn() ? R.string.call_earpiece : R.string.call_speaker);
        speakerButton.setContentDescription(getString(
                session.isSpeakerOn() ? R.string.call_earpiece : R.string.call_speaker));
    }

    private int lastAnnouncedStatus = 0;

    private void renderTimer() {
        if (currentSession == null || currentSession.getAnsweredAtMs() == null) return;
        long elapsedSeconds = Math.max(0L,
                (System.currentTimeMillis() - currentSession.getAnsweredAtMs()) / 1000L);
        String formatted = CallTimerFormatter.formatSeconds(elapsedSeconds);
        timer.setText(formatted);
        timer.setContentDescription(getString(R.string.call_timer_content_description, formatted));
    }

    private boolean hasOutgoingIntent() {
        return getIntent().hasExtra(CallLauncher.EXTRA_CONVERSATION_ID)
                && getIntent().hasExtra(CallLauncher.EXTRA_PEER_USER_ID);
    }

    private void requestOperation(PendingOperation operation) {
        pendingOperation = operation;
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.RECORD_AUDIO);
        Decision decision = CallPermissionHelper.microphoneDecision(granted, rationale);
        if (decision == Decision.GRANTED) {
            requestNotificationPermissionThenContinue();
        } else if (decision == Decision.SHOW_RATIONALE) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.call_mic_rationale)
                    .setPositiveButton(R.string.call_permission_continue,
                            (dialog, which) -> requestMicrophonePermission())
                    .setNegativeButton(R.string.call_permission_cancel,
                            (dialog, which) -> denyPendingOperation())
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
        Decision decision = CallPermissionHelper.notificationDecision(
                Build.VERSION.SDK_INT, granted, rationale);
        if (decision == Decision.REQUEST) {
            ActivityCompat.requestPermissions(
                    this, new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else if (decision == Decision.SHOW_RATIONALE) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.call_notifications_rationale)
                    .setPositiveButton(R.string.call_permission_continue,
                            (dialog, which) -> ActivityCompat.requestPermissions(
                                    this, new String[] {Manifest.permission.POST_NOTIFICATIONS},
                                    REQUEST_NOTIFICATIONS))
                    .setNegativeButton(R.string.call_permission_cancel, (dialog, which) -> {
                        Toast.makeText(this, R.string.call_notifications_permission_needed,
                                Toast.LENGTH_LONG).show();
                        continuePendingOperation();
                    })
                    .show();
        } else {
            continuePendingOperation();
        }
    }

    private void continuePendingOperation() {
        PendingOperation operation = pendingOperation;
        pendingOperation = PendingOperation.NONE;
        if (operation == PendingOperation.START_OUTGOING) {
            if (startRequested) return;
            startRequested = true;
            CallForegroundService.startForCall(this);
            callManager.startOutgoing(
                    getIntent().getStringExtra(CallLauncher.EXTRA_CONVERSATION_ID),
                    getIntent().getStringExtra(CallLauncher.EXTRA_PEER_USER_ID),
                    getIntent().getStringExtra(CallLauncher.EXTRA_PEER_NAME));
        } else if (operation == PendingOperation.ACCEPT_INCOMING && currentSession != null) {
            CallForegroundService.startForCall(this);
            callManager.accept(currentSession.getCallId());
        }
    }

    private void denyPendingOperation() {
        PendingOperation operation = pendingOperation;
        pendingOperation = PendingOperation.NONE;
        if (operation == PendingOperation.ACCEPT_INCOMING && currentSession != null) {
            callManager.reject(currentSession.getCallId());
        }
        showPhoneFallbackDialog(R.string.call_mic_permission_needed);
    }

    private void showCallError(CallError error) {
        int messageRes = R.string.call_state_failed;
        if (error != null) {
            switch (error.getCode()) {
                case BUSY: messageRes = R.string.call_state_busy; break;
                case PEER_OFFLINE: messageRes = R.string.call_state_no_answer; break;
                case MEDIA_UNAVAILABLE:
                case NETWORK: messageRes = R.string.call_state_failed; break;
                default: break;
            }
        }
        showPhoneFallbackDialog(messageRes);
    }

    private void showPhoneFallbackDialog(int messageRes) {
        if (callErrorDialogShown || isFinishing()) return;
        callErrorDialogShown = true;
        mainHandler.removeCallbacks(finishEndedScreen);
        String phone = getIntent().getStringExtra(CallLauncher.EXTRA_PEER_PHONE);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setMessage(messageRes)
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish());
        if (CallPermissionHelper.hasPhoneFallback(phone)) {
            builder.setPositiveButton(R.string.call_via_phone,
                    (dialog, which) -> dialPhone(phone));
        }
        builder.setOnDismissListener(dialog -> {
            if (!isFinishing()) finish();
        });
        builder.show();
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

    private void updateProximityWakeLock() {
        if (currentSession != null && currentSession.getState() == CallState.ACTIVE
                && !currentSession.isSpeakerOn() && isResumed()) {
            acquireProximityWakeLock();
        } else {
            releaseProximityWakeLock();
        }
    }

    private boolean isResumed() {
        return getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
    }

    private void acquireProximityWakeLock() {
        if (proximityWakeLock != null && proximityWakeLock.isHeld()) return;
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) return;
        try {
            proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, getPackageName() + ":call-proximity");
            proximityWakeLock.setReferenceCounted(false);
            proximityWakeLock.acquire();
        } catch (RuntimeException ignored) {
            proximityWakeLock = null;
        }
    }

    private void releaseProximityWakeLock() {
        if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
            proximityWakeLock.release();
        }
        proximityWakeLock = null;
    }

    private static boolean granted(int[] grantResults) {
        return grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isInProgress(CallSession session) {
        return session != null && session.getState() != CallState.IDLE
                && session.getState() != CallState.ENDED;
    }

    @Override public void onBackPressed() {
        if (currentSession == null) {
            super.onBackPressed();
            return;
        }
        CallState state = currentSession.getState();
        if (state == CallState.OUTGOING_RINGING || state == CallState.INCOMING_RINGING) return;
        if (state == CallState.CONNECTING || state == CallState.ACTIVE) {
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }
}
