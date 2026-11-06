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
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.media.CallMediaEngineProvider;
import com.nexachat.call.media.CallRingtonePlayer;
import com.nexachat.call.service.CallForegroundService;
import com.nexachat.call.service.CallNotifications;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.ui.MessageUiRules;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/** Lock-screen incoming call view; call state remains owned by the process CallManager. */
@AndroidEntryPoint
public class IncomingCallActivity extends ComponentActivity {
    public static final String EXTRA_CALL_ID = "com.nexachat.call.CALL_ID";

    private static final int REQUEST_MICROPHONE = 7220;
    private static final int REQUEST_NOTIFICATIONS = 7221;
    private static final String STATE_PENDING_ACCEPT = "call.pendingAccept";
    private static final long ENDED_SCREEN_MS = 1600L;

    @Inject CallManager callManager;

    private TextView initials;
    private TextView peerName;
    private TextView status;
    private Button acceptButton;
    private Button declineButton;
    private CallSession currentSession;
    private CallManager.CallListener listener;
    private CallRingtonePlayer ringtonePlayer;
    private boolean pendingAccept;
    private boolean resolving;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable finishEndedScreen = this::finish;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.INSTANCE.wrapContext(newBase));
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
        ringtonePlayer = new CallRingtonePlayer(getApplicationContext());
        callManager.attachMediaEngine(CallMediaEngineProvider.get(getApplicationContext()));
        acceptButton.setOnClickListener(view -> requestAccept());
        declineButton.setOnClickListener(view -> decline());
        currentSession = callManager.session().getValue();
        if (!matchesTargetCall(currentSession)) {
            finish();
            return;
        }
        render(currentSession);
        if (currentSession == null || currentSession.getState() != CallState.INCOMING_RINGING) {
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
                if (!matchesTargetCall(session)) {
                    resolving = true;
                    ringtonePlayer.stop();
                    finish();
                    return;
                }
                currentSession = session;
                render(session);
                if (session == null || session.getState() != CallState.INCOMING_RINGING) {
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
        currentSession = callManager.session().getValue();
        render(currentSession);
        if (!resolving && matchesTargetCall(currentSession)
                && currentSession.getState() == CallState.INCOMING_RINGING) {
            ringtonePlayer.start();
        }
    }

    @Override protected void onStop() {
        if (listener != null) {
            callManager.removeListener(listener);
            listener = null;
        }
        if (!resolving && matchesTargetCall(currentSession)
                && currentSession.getState() == CallState.INCOMING_RINGING) {
            ringtonePlayer.stop();
            CallNotifications.showIncoming(this, currentSession);
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
        currentSession = callManager.session().getValue();
        if (!matchesTargetCall(currentSession)) {
            resolving = true;
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
        boolean ringing = session.getState() == CallState.INCOMING_RINGING;
        acceptButton.setEnabled(ringing);
        declineButton.setEnabled(ringing);
    }

    private void requestAccept() {
        if (!IncomingCallPermissionState.canStartAccept(
                pendingAccept,
                currentSession != null
                        && currentSession.getState() == CallState.INCOMING_RINGING)) return;
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
                || currentSession.getState() != CallState.INCOMING_RINGING) return;
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
                && currentSession.getState() == CallState.INCOMING_RINGING) {
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

}
