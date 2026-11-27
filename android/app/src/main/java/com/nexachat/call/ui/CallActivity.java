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
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
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
import com.nexachat.call.core.CallMediaType;
import com.nexachat.call.media.CallMediaEngineProvider;
import com.nexachat.call.service.CallForegroundService;
import com.nexachat.call.ui.CallPermissionHelper.Decision;
import com.nexachat.core.common.AppLocaleManager;
import com.nexachat.core.realtime.ephemeral.EphemeralStateSource;
import com.nexachat.core.realtime.ephemeral.FirebaseEphemeralStateSource;
import com.nexachat.core.ui.MessageUiRules;
import dagger.hilt.android.AndroidEntryPoint;
import io.reactivex.rxjava3.disposables.Disposable;
import javax.inject.Inject;

/** Classic XML call screen; the singleton manager owns state across rotation. */
@AndroidEntryPoint
public class CallActivity extends ComponentActivity {
    private static final int REQUEST_MICROPHONE = 7210;
    private static final int REQUEST_NOTIFICATIONS = 7211;
    private static final int REQUEST_CAMERA = 7212;
    private static final String PREFS = "call_permissions";
    private static final String PREF_CAMERA_REQUESTED = "call.cameraRequested";
    private static final long ENDED_SCREEN_MS = 1600L;
    /** Upper bound so a missed release can never hold the screen off forever. */
    private static final long PROXIMITY_WAKE_LOCK_TIMEOUT_MS =
            java.util.concurrent.TimeUnit.HOURS.toMillis(8);
    private static final String STATE_PENDING_PERMISSION = "call.pendingPermission";
    private static final String STATE_CALL_ID = "call.callId";

    private enum PendingOperation { NONE, START_OUTGOING, ACCEPT_INCOMING }

    @Inject CallManager callManager;
    @Inject FirebaseEphemeralStateSource firebaseEphemeralStateSource;
    @Inject FirebaseCallStatusDisplayAdapter firebaseCallStatusDisplayAdapter;

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
    private View videoHeader;
    private TextView videoPeerName;
    private TextView videoDetail;
    private boolean videoHeaderInUse;
    private Button muteButton;
    private Button speakerButton;
    private Button hangUpButton;
    private View pulseRing;
    private android.animation.AnimatorSet pulseAnimator;
    private View avatarWrap;
    private org.webrtc.SurfaceViewRenderer remoteVideoView;
    private org.webrtc.SurfaceViewRenderer localVideoView;
    private View localPreviewCard;
    private Button cameraButton;
    private Button switchCameraButton;
    private View controlsRow;
    private boolean controlsHidden;
    private boolean revealingControlsGesture;
    private boolean autoHideScheduled;
    private static final long CONTROLS_HIDE_DELAY_MS = 5_000L;
    private AccessibilityManager accessibilityManager;
    private final AccessibilityManager.TouchExplorationStateChangeListener touchExplorationListener =
            enabled -> syncAutoHide(this.currentSession);
    private boolean renderersInitialized;
    private boolean localVideoBound;
    private boolean remoteVideoBound;
    private final RemoteVideoFlow remoteFlow = new RemoteVideoFlow();
    private boolean remoteFlowShowing;
    private static final long BLACK_CHECK_MS = 1000L;
    private volatile boolean remoteFrameBlack;
    private volatile long remoteBlackCheckMs;
    private final org.webrtc.VideoSink remoteFlowSink = frame -> {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - remoteBlackCheckMs >= BLACK_CHECK_MS) {
            remoteBlackCheckMs = now;
            remoteFrameBlack = BlackFrameDetector.isBlack(frame);
        }
        // A peer that keeps sending black frames after turning its camera off counts as stopped.
        if (remoteFrameBlack) return;
        if (remoteFlow.onFrame(now)) {
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) renderCurrent();
            });
        }
    };
    private final Runnable remoteFlowWatcher = new Runnable() {
        @Override public void run() {
            if (!remoteVideoBound) return;
            boolean flowing = remoteFlow.isFlowing(android.os.SystemClock.elapsedRealtime());
            if (flowing != remoteFlowShowing) renderCurrent();
            mainHandler.postDelayed(this, 500L);
        }
    };
    private Object localVideoTrack;
    private Object remoteVideoTrack;
    private CallMediaType requestedMediaType = CallMediaType.AUDIO;
    private boolean deviceHasCamera = true;
    private boolean canSwitchCamera;
    private boolean frontFacingCamera = true;
    private CallSession currentSession;
    private CallManager.CallListener listener;
    private FirebaseCallStatusDisplaySession firebaseCallStatusDisplaySession;
    private Disposable firebaseStatusEvents;
    private PendingOperation pendingOperation = PendingOperation.NONE;
    private boolean startRequested;
    private String requestedCallId;
    /** Terminal call the manager still held when this outgoing screen opened. */
    @Nullable private String callIdBeforeLaunch;
    private CallActivityLaunchPolicy.Decision launchDecision;
    private boolean callErrorDialogShown;
    private PowerManager.WakeLock proximityWakeLock;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);
        bindViews();
        accessibilityManager = getSystemService(AccessibilityManager.class);
        firebaseCallStatusDisplaySession = new FirebaseCallStatusDisplaySession(
                firebaseCallStatusDisplayAdapter,
                firebaseEphemeralStateSource::listenToCallStatus,
                ignored -> mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) render(currentSession);
                }));
        deviceHasCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        canSwitchCamera = getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
        requestedMediaType = CallMediaType.parse(
                getIntent().getStringExtra(CallLauncher.EXTRA_MEDIA_TYPE));
        if (savedInstanceState != null) {
            pendingOperation = PendingOperation.values()[Math.max(0, Math.min(
                    PendingOperation.values().length - 1,
                    savedInstanceState.getInt(STATE_PENDING_PERMISSION, 0)))];
            requestedCallId = savedInstanceState.getString(STATE_CALL_ID);
        }
        callManager.attachMediaEngine(CallMediaEngineProvider.get(getApplicationContext()));
        if (requestedCallId == null) {
            requestedCallId = getIntent().getStringExtra(IncomingCallActivity.EXTRA_CALL_ID);
        }
        CallSession managerSession = callManager.session().getValue();
        boolean freshOutgoingRequest = hasOutgoingIntent()
                && !CallNotificationActionRouter.ACTION_OPEN.equals(getIntent().getAction());
        launchDecision = CallActivityLaunchPolicy.decide(
                freshOutgoingRequest,
                savedInstanceState != null,
                pendingOperation == PendingOperation.START_OUTGOING,
                requestedCallId,
                getIntent().getStringExtra(CallLauncher.EXTRA_CONVERSATION_ID),
                getIntent().getStringExtra(CallLauncher.EXTRA_PEER_USER_ID),
                managerSession);
        if (launchDecision == CallActivityLaunchPolicy.Decision.FINISH) {
            finish();
            return;
        }
        if (launchDecision == CallActivityLaunchPolicy.Decision.START_OUTGOING
                && managerSession != null && managerSession.getState() == CallState.ENDED) {
            callIdBeforeLaunch = managerSession.getCallId();
        }
        currentSession = launchDecision == CallActivityLaunchPolicy.Decision.ATTACH_TO_LIVE_CALL
                ? managerSession : null;
        startRequested = currentSession != null;
        render(currentSession);
        if (launchDecision == CallActivityLaunchPolicy.Decision.START_OUTGOING) {
            requestOperation(PendingOperation.START_OUTGOING);
        }
        bindActions();
        updateProximityWakeLock();
    }

    @Override protected void onStart() {
        super.onStart();
        if (launchDecision == CallActivityLaunchPolicy.Decision.FINISH || isFinishing()) return;
        if (accessibilityManager != null) {
            accessibilityManager.addTouchExplorationStateChangeListener(touchExplorationListener);
        }
        setControlsHidden(false);
        startFirebaseCallStatusDisplay();
        if (remoteVideoBound) {
            mainHandler.removeCallbacks(remoteFlowWatcher);
            mainHandler.postDelayed(remoteFlowWatcher, 500L);
        }
        listener = new CallManager.CallListener() {
            @Override public void onCallChanged(CallSession session) {
                if (CallActivityLaunchPolicy.isStaleEnded(callIdBeforeLaunch, session)) return;
                currentSession = session;
                firebaseCallStatusDisplaySession.onCallChanged(session);
                render(session);
                updateProximityWakeLock();
                if (session != null && session.getState() == CallState.ENDED) {
                    mainHandler.removeCallbacks(finishEndedScreen);
                    mainHandler.postDelayed(finishEndedScreen, ENDED_SCREEN_MS);
                } else {
                    mainHandler.removeCallbacks(finishEndedScreen);
                }
            }

            @Override public void onCallError(CallError error) {
                showCallError(error);
            }
        };
        callManager.addListener(listener);
        callManager.setCallVideoListener(new CallManager.CallVideoListener() {
            @Override public void onLocalVideoTrack(@Nullable Object videoTrack, boolean frontFacing) {
                CallActivity.this.onLocalVideoTrack(videoTrack, frontFacing);
            }

            @Override public void onRemoteVideoTrack(@Nullable Object videoTrack) {
                CallActivity.this.onRemoteVideoTrack(videoTrack);
            }
        });
        CallSession latestSession = callManager.session().getValue();
        if (launchDecision == CallActivityLaunchPolicy.Decision.ATTACH_TO_LIVE_CALL) {
            if (!CallActivityLaunchPolicy.matchesLiveCall(
                    requestedCallId,
                    getIntent().getStringExtra(CallLauncher.EXTRA_CONVERSATION_ID),
                    getIntent().getStringExtra(CallLauncher.EXTRA_PEER_USER_ID),
                    latestSession)) {
                finish();
                return;
            }
            currentSession = latestSession;
        } else if (launchDecision == CallActivityLaunchPolicy.Decision.START_OUTGOING) {
            if (CallActivityLaunchPolicy.isLive(latestSession)) {
                if (!CallActivityLaunchPolicy.matchesLiveCall(
                        requestedCallId,
                        getIntent().getStringExtra(CallLauncher.EXTRA_CONVERSATION_ID),
                        getIntent().getStringExtra(CallLauncher.EXTRA_PEER_USER_ID),
                        latestSession)) {
                    finish();
                    return;
                }
                launchDecision = CallActivityLaunchPolicy.Decision.ATTACH_TO_LIVE_CALL;
                currentSession = latestSession;
                startRequested = true;
            } else {
                // The manager still holds the previous call's ENDED session until the new
                // call is published; adopting it would close this screen after 1.6 s.
                currentSession = CallActivityLaunchPolicy.endedSessionToAdopt(
                        startRequested, callIdBeforeLaunch, latestSession);
            }
        }
        render(currentSession);
        firebaseCallStatusDisplaySession.onCallChanged(currentSession);
        if (currentSession != null && currentSession.getState() == CallState.ENDED) {
            mainHandler.removeCallbacks(finishEndedScreen);
            mainHandler.postDelayed(finishEndedScreen, ENDED_SCREEN_MS);
        } else {
            mainHandler.removeCallbacks(finishEndedScreen);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        updateProximityWakeLock();
    }

    @Override protected void onStop() {
        if (accessibilityManager != null) {
            accessibilityManager.removeTouchExplorationStateChangeListener(touchExplorationListener);
        }
        cancelAutoHide();
        revealingControlsGesture = false;
        if (firebaseStatusEvents != null) {
            firebaseStatusEvents.dispose();
            firebaseStatusEvents = null;
        }
        firebaseCallStatusDisplaySession.stop();
        mainHandler.removeCallbacks(remoteFlowWatcher);
        callManager.setCallVideoListener(null);
        if (listener != null) {
            callManager.removeListener(listener);
            listener = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        releaseProximityWakeLock();
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (firebaseStatusEvents != null) firebaseStatusEvents.dispose();
        if (firebaseCallStatusDisplaySession != null) {
            firebaseCallStatusDisplaySession.stop();
        }
        mainHandler.removeCallbacks(timerTick);
        mainHandler.removeCallbacks(finishEndedScreen);
        cancelAutoHide();
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        releaseProximityWakeLock();
        releaseVideoRenderers();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        CallSession session = callManager.session().getValue();
        if (CallActivityLaunchPolicy.isLive(session) && session.getCallId() != null) {
            outState.putString(STATE_CALL_ID, session.getCallId());
        }
        outState.putInt(STATE_PENDING_PERMISSION, pendingOperation.ordinal());
        super.onSaveInstanceState(outState);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            if (granted(grantResults)) {
                requestCameraThenNotifications();
            } else {
                denyPendingOperation();
            }
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
            continuePendingOperation();
        }
    }

    private void bindViews() {
        peerName = findViewById(R.id.call_peer_name);
        peerInitials = findViewById(R.id.call_peer_initials);
        status = findViewById(R.id.call_status);
        timer = findViewById(R.id.call_timer);
        videoHeader = findViewById(R.id.call_video_header);
        videoPeerName = findViewById(R.id.call_video_peer_name);
        videoDetail = findViewById(R.id.call_video_detail);
        muteButton = findViewById(R.id.call_mute);
        speakerButton = findViewById(R.id.call_speaker);
        hangUpButton = findViewById(R.id.call_hang_up);
        pulseRing = findViewById(R.id.call_pulse_ring);
        avatarWrap = findViewById(R.id.call_avatar_wrap);
        remoteVideoView = findViewById(R.id.call_remote_video);
        localVideoView = findViewById(R.id.call_local_video);
        localPreviewCard = findViewById(R.id.call_local_preview);
        cameraButton = findViewById(R.id.call_camera);
        switchCameraButton = findViewById(R.id.call_camera_switch);
        controlsRow = findViewById(R.id.call_controls_row);
    }

    private void startFirebaseCallStatusDisplay() {
        firebaseCallStatusDisplaySession.start();
        if (!firebaseCallStatusDisplayAdapter.isEnabled()) return;
        firebaseStatusEvents = firebaseEphemeralStateSource.statusUpdates().subscribe(
                sourceStatus -> mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (sourceStatus instanceof EphemeralStateSource.SourceStatus.Ready) {
                        firebaseCallStatusDisplaySession.onSourceReady();
                    } else {
                        firebaseCallStatusDisplaySession.onSourceUnavailable();
                    }
                    render(currentSession);
                }), ignored -> mainHandler.post(() ->
                        firebaseCallStatusDisplaySession.onSourceUnavailable()));
    }

    private void bindActions() {
        muteButton.setOnClickListener(view -> {
            if (currentSession != null) callManager.setMuted(!currentSession.isMuted());
        });
        speakerButton.setOnClickListener(view -> {
            if (currentSession != null) callManager.setSpeaker(!currentSession.isSpeakerOn());
        });
        if (cameraButton != null) {
            cameraButton.setOnClickListener(view -> {
                if (currentSession != null) {
                    callManager.setCameraEnabled(!currentSession.isCameraOn());
                }
            });
        }
        if (switchCameraButton != null) {
            switchCameraButton.setOnClickListener(view -> callManager.switchCamera());
        }
        hangUpButton.setOnClickListener(view -> callManager.hangUp());
    }

    /** Controls and the elapsed-time header share the same inactivity deadline. */
    private final Runnable autoHideTick = () -> {
        autoHideScheduled = false;
        if (canAutoHide(currentSession)) setControlsHidden(true);
    };

    private boolean canAutoHide(@Nullable CallSession session) {
        return session != null && CallAutoHidePolicy.shouldAutoHide(session.getMediaType(), session.getState())
                && (accessibilityManager == null || !accessibilityManager.isTouchExplorationEnabled());
    }

    private void scheduleAutoHide() {
        if (autoHideScheduled) return;
        autoHideScheduled = true;
        mainHandler.removeCallbacks(autoHideTick);
        long delay = CONTROLS_HIDE_DELAY_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && accessibilityManager != null) {
            delay = accessibilityManager.getRecommendedTimeoutMillis((int) CONTROLS_HIDE_DELAY_MS,
                    AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_ICONS
                            | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        mainHandler.postDelayed(autoHideTick, delay);
    }

    private void cancelAutoHide() {
        autoHideScheduled = false;
        mainHandler.removeCallbacks(autoHideTick);
    }

    private void setControlsHidden(boolean hidden) {
        controlsHidden = hidden;
        controlsRow.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        updateVideoHeaderVisibility();
        if (!hidden && videoHeaderInUse && currentSession != null
                && currentSession.getState() == CallState.ACTIVE) {
            renderTimer();
        }
    }

    /** Reconciles the auto-hide overlay with the latest session state. */
    private void syncAutoHide(CallSession session) {
        if (!canAutoHide(session)) {
            cancelAutoHide();
            if (controlsHidden) setControlsHidden(false);
            return;
        }
        if (!controlsHidden) scheduleAutoHide();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            revealingControlsGesture = controlsHidden && canAutoHide(currentSession);
            if (revealingControlsGesture) setControlsHidden(false);
            if (canAutoHide(currentSession)) {
                cancelAutoHide();
                scheduleAutoHide();
            }
        }
        if (revealingControlsGesture) {
            // Consume the full gesture that exposed the controls.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                revealingControlsGesture = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void renderCurrent() {
        render(currentSession);
    }

    private void render(CallSession session) {
        if (peerName == null) return;
        if (session == null || session.getState() == CallState.IDLE) {
            setVideoHeaderInUse(false);
            syncAutoHide(null);
            timer.setVisibility(View.GONE);
            mainHandler.removeCallbacks(timerTick);
            String requestedName = getIntent().getStringExtra(CallLauncher.EXTRA_PEER_NAME);
            if (requestedName == null || requestedName.trim().isEmpty()) {
                requestedName = getString(R.string.call_peer_fallback);
            }
            peerName.setText(requestedName);
            peerInitials.setText(MessageUiRules.extractInitials(
                    peerName.getText() == null ? null : peerName.getText().toString()));
            status.setText(R.string.call_state_calling);
            status.setContentDescription(status.getText());
            updatePulseAnimation(true);
            return;
        }
        currentSession = session;
        boolean isLive = session.getState() == CallState.ACTIVE
                || session.getState() == CallState.OUTGOING_RINGING
                || session.getState() == CallState.CONNECTING
                || session.getState() == CallState.RECONNECTING_NETWORK;
        updatePulseAnimation(isLive);
        if (!isLive) detachVideoSinks();

        String name = session.getPeerDisplayName();
        if (name == null || name.trim().isEmpty()) name = getString(R.string.call_peer_fallback);
        peerInitials.setText(MessageUiRules.extractInitials(name));
        CallAvatarColorResolver.apply(this, peerInitials, name);
        CallUiModel model = CallUiModelMapper.map(
                session, firebaseCallStatusDisplaySession == null
                        ? null : firebaseCallStatusDisplaySession.display());
        boolean videoCall = session.getMediaType() == CallMediaType.VIDEO;
        boolean answered = session.getAnsweredAtMs() != null;
        boolean active = session.getState() == CallState.ACTIVE;
        boolean showTimer = model.isShowTimer() && answered;
        boolean showVideoHeader = videoCall && showTimer;
        boolean showDuration = showTimer && (!showVideoHeader || active);
        setVideoHeaderInUse(showVideoHeader);
        TextView visibleName = showVideoHeader ? videoPeerName : peerName;
        visibleName.setText(name);
        TextView callStatus = showVideoHeader ? videoDetail : status;
        if (!showVideoHeader || !active) {
            int statusRes = model.getSubtitleRes() != 0 ? model.getSubtitleRes() : model.getTitleRes();
            callStatus.setText(statusRes);
            callStatus.setContentDescription(callStatus.getText());
        }
        if (model.getTitleRes() != lastAnnouncedStatus) {
            View announcementSource = showVideoHeader && active ? visibleName : callStatus;
            announcementSource.announceForAccessibility(getString(model.getTitleRes()));
            lastAnnouncedStatus = model.getTitleRes();
        }
        timer.setVisibility(showTimer && !showVideoHeader ? View.VISIBLE : View.GONE);
        mainHandler.removeCallbacks(timerTick);
        if (showDuration) renderTimer();
        if (showDuration && active) mainHandler.postDelayed(timerTick, 1000L);
        muteButton.setVisibility(model.isShowMute() ? View.VISIBLE : View.GONE);
        speakerButton.setVisibility(model.isShowSpeaker() ? View.VISIBLE : View.GONE);
        hangUpButton.setVisibility(model.isShowHangUp() ? View.VISIBLE : View.GONE);
        if (muteButton instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) muteButton)
                    .setIconResource(session.isMuted() ? R.drawable.ic_mic_off : R.drawable.ic_mic);
        }
        if (speakerButton instanceof com.google.android.material.button.MaterialButton) {
            ((com.google.android.material.button.MaterialButton) speakerButton)
                    .setIconResource(session.isSpeakerOn() ? R.drawable.ic_volume_up : R.drawable.ic_volume_down);
        }
        if (model.getHangUpLabelRes() != 0) {
            hangUpButton.setContentDescription(getString(model.getHangUpLabelRes()));
        }
        muteButton.setContentDescription(getString(
                session.isMuted() ? R.string.call_unmute : R.string.call_mute));
        speakerButton.setContentDescription(getString(
                session.isSpeakerOn() ? R.string.call_earpiece : R.string.call_speaker));
        remoteFlowShowing = remoteFlow.isFlowing(android.os.SystemClock.elapsedRealtime());
        boolean remoteShowing = videoCall && remoteVideoBound && isLive && remoteFlowShowing;
        if (remoteVideoView != null) {
            remoteVideoView.setVisibility(remoteShowing ? View.VISIBLE : View.GONE);
        }
        if (avatarWrap != null) {
            avatarWrap.setVisibility(remoteShowing ? View.GONE : View.VISIBLE);
        }
        boolean showLocalPreview = videoCall && localVideoBound && session.isCameraOn();
        if (localPreviewCard != null) {
            localPreviewCard.setVisibility(showLocalPreview ? View.VISIBLE : View.GONE);
        }
        boolean showCameraControls = videoCall && isLive && deviceHasCamera;
        if (cameraButton != null) {
            cameraButton.setVisibility(showCameraControls ? View.VISIBLE : View.GONE);
            int cameraIcon = session.isCameraOn() ? R.drawable.ic_videocam : R.drawable.ic_videocam_off;
            int cameraLabelRes = session.isCameraOn() ? R.string.call_camera_off : R.string.call_camera_on;
            if (cameraButton instanceof com.google.android.material.button.MaterialButton) {
                ((com.google.android.material.button.MaterialButton) cameraButton)
                        .setIconResource(cameraIcon);
            }
            cameraButton.setContentDescription(getString(cameraLabelRes));
        }
        if (switchCameraButton != null) {
            switchCameraButton.setVisibility(showCameraControls && canSwitchCamera ? View.VISIBLE : View.GONE);
            switchCameraButton.setEnabled(session.isCameraOn() && localVideoBound);
            int label = frontFacingCamera ? R.string.call_switch_to_back_camera : R.string.call_switch_to_front_camera;
            String description = getString(label);
            switchCameraButton.setContentDescription(description);
            androidx.appcompat.widget.TooltipCompat.setTooltipText(switchCameraButton, description);
        }
        syncAutoHide(session);
    }

    private void setVideoHeaderInUse(boolean inUse) {
        videoHeaderInUse = inUse;
        updateVideoHeaderVisibility();
        int centerVisibility = inUse ? View.GONE : View.VISIBLE;
        peerName.setVisibility(centerVisibility);
        status.setVisibility(centerVisibility);
    }

    private void updateVideoHeaderVisibility() {
        videoHeader.setVisibility(!videoHeaderInUse ? View.GONE
                : controlsHidden ? View.INVISIBLE : View.VISIBLE);
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
                pulseRing.setVisibility(View.VISIBLE);
            }
        } else {
            if (pulseAnimator != null) {
                pulseAnimator.cancel();
                pulseAnimator = null;
            }
            pulseRing.setScaleX(1.0f);
            pulseRing.setScaleY(1.0f);
            pulseRing.setAlpha(0.25f);
            pulseRing.setVisibility(isLive ? View.VISIBLE : View.GONE);
        }
    }

    private int lastAnnouncedStatus = 0;

    private void renderTimer() {
        if (currentSession == null || currentSession.getAnsweredAtMs() == null
                || (videoHeaderInUse && controlsHidden)) return;
        long elapsedSeconds = Math.max(0L,
                (System.currentTimeMillis() - currentSession.getAnsweredAtMs()) / 1000L);
        String formatted = CallTimerFormatter.formatSeconds(
                elapsedSeconds, getString(R.string.time_duration_mm_ss));
        TextView visibleTimer = videoHeaderInUse ? videoDetail : timer;
        visibleTimer.setText(formatted);
        visibleTimer.setContentDescription(getString(R.string.call_timer_content_description, formatted));
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
            requestCameraThenNotifications();
        } else if (decision == Decision.SHOW_RATIONALE) {
            new MaterialAlertDialogBuilder(this)
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

    /** Camera is optional: voice flow unchanged; video requests CAMERA then falls back to voice. */
    private void requestCameraThenNotifications() {
        if (pendingOperation != PendingOperation.START_OUTGOING
                || requestedMediaType != CallMediaType.VIDEO
                || !deviceHasCamera) {
            requestNotificationPermissionThenContinue();
            return;
        }
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean rationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.CAMERA);
        Decision decision = CallPermissionHelper.cameraDecision(
                granted, rationale, cameraRequestedBefore());
        if (decision == Decision.GRANTED) {
            requestNotificationPermissionThenContinue();
        } else if (decision == Decision.SHOW_RATIONALE) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.call_camera_rationale)
                    .setPositiveButton(R.string.call_permission_continue,
                            (dialog, which) -> requestCameraPermission())
                    .setNegativeButton(R.string.call_permission_cancel,
                            (dialog, which) -> cancelPendingOperation())
                    .show();
        } else if (decision == Decision.OPEN_SETTINGS) {
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

    /** Denied camera demotes the requested video call to voice or closes the screen. */
    private void showCameraFallbackDialog(boolean permanentDeny) {
        if (isFinishing()) return;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.call_camera_permission_needed)
                .setPositiveButton(R.string.call_start_as_voice, (dialog, which) -> {
                    requestedMediaType = CallMediaType.AUDIO;
                    requestNotificationPermissionThenContinue();
                })
                .setNegativeButton(R.string.call_permission_cancel,
                        (dialog, which) -> cancelPendingOperation());
        if (permanentDeny) {
            builder.setNeutralButton(R.string.call_open_settings,
                    (dialog, which) -> openAppSettings());
        }
        builder.show();
    }

    private void cancelPendingOperation() {
        pendingOperation = PendingOperation.NONE;
        finish();
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
        Decision decision = CallPermissionHelper.notificationDecision(
                Build.VERSION.SDK_INT, granted, rationale);
        if (decision == Decision.REQUEST) {
            ActivityCompat.requestPermissions(
                    this, new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        } else if (decision == Decision.SHOW_RATIONALE) {
            new MaterialAlertDialogBuilder(this)
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
            CallForegroundService.startForCall(this, requestedMediaType == CallMediaType.VIDEO);
            callManager.startOutgoing(
                    getIntent().getStringExtra(CallLauncher.EXTRA_CONVERSATION_ID),
                    getIntent().getStringExtra(CallLauncher.EXTRA_PEER_USER_ID),
                    getIntent().getStringExtra(CallLauncher.EXTRA_PEER_NAME),
                    requestedMediaType);
        } else if (operation == PendingOperation.ACCEPT_INCOMING && currentSession != null) {
            CallForegroundService.startForCall(this,
                    currentSession.getMediaType() == CallMediaType.VIDEO);
            callManager.accept(currentSession.getCallId());
        }
    }

    private void denyPendingOperation() {
        PendingOperation operation = pendingOperation;
        pendingOperation = PendingOperation.NONE;
        if (operation == PendingOperation.ACCEPT_INCOMING && currentSession != null) {
            callManager.reject(currentSession.getCallId());
        }
        showPhoneFallbackDialog(CallFailureDialogCopy.forPermissionDenied());
    }

    private void showCallError(CallError error) {
        showPhoneFallbackDialog(CallFailureDialogCopy.forCode(
                error == null ? null : error.getCode()));
    }

    private void showPhoneFallbackDialog(CallFailureDialogCopy copy) {
        if (callErrorDialogShown || isFinishing()) return;
        callErrorDialogShown = true;
        mainHandler.removeCallbacks(finishEndedScreen);
        String phone = getIntent().getStringExtra(CallLauncher.EXTRA_PEER_PHONE);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(copy.getTitleResource())
                .setMessage(copy.getReasonResource())
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
            // The number was validated; the failure means no dialer app exists.
            Toast.makeText(this, R.string.call_no_dialer_app, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void updateProximityWakeLock() {
        if (currentSession != null && currentSession.getState() == CallState.ACTIVE
                && !currentSession.isSpeakerOn()
                && currentSession.getMediaType() != CallMediaType.VIDEO
                && isResumed()) {
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
            proximityWakeLock.acquire(PROXIMITY_WAKE_LOCK_TIMEOUT_MS);
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

    private void onLocalVideoTrack(@Nullable Object track, boolean frontFacing) {
        frontFacingCamera = frontFacing;
        if (localVideoBound && localVideoTrack != track && localVideoTrack instanceof org.webrtc.VideoTrack) {
            ((org.webrtc.VideoTrack) localVideoTrack).removeSink(localVideoView);
            localVideoBound = false;
        }
        localVideoTrack = track;
        if (track instanceof org.webrtc.VideoTrack) {
            ensureVideoRenderers();
            if (renderersInitialized) {
                localVideoView.setMirror(frontFacing);
                if (!localVideoBound) {
                    ((org.webrtc.VideoTrack) track).addSink(localVideoView);
                    localVideoBound = true;
                }
            }
        }
        render(currentSession);
    }

    private void onRemoteVideoTrack(@Nullable Object track) {
        if (remoteVideoBound && remoteVideoTrack instanceof org.webrtc.VideoTrack) {
            ((org.webrtc.VideoTrack) remoteVideoTrack).removeSink(remoteVideoView);
            ((org.webrtc.VideoTrack) remoteVideoTrack).removeSink(remoteFlowSink);
            remoteVideoBound = false;
        }
        remoteFlow.reset();
        remoteFrameBlack = false;
        remoteBlackCheckMs = 0L;
        mainHandler.removeCallbacks(remoteFlowWatcher);
        remoteVideoTrack = track;
        if (track instanceof org.webrtc.VideoTrack) {
            ensureVideoRenderers();
            if (renderersInitialized) {
                ((org.webrtc.VideoTrack) track).addSink(remoteVideoView);
                ((org.webrtc.VideoTrack) track).addSink(remoteFlowSink);
                remoteVideoBound = true;
                mainHandler.postDelayed(remoteFlowWatcher, 500L);
            }
        }
        render(currentSession);
    }

    private void ensureVideoRenderers() {
        if (renderersInitialized || remoteVideoView == null || localVideoView == null) return;
        CallMediaEngine engine = CallMediaEngineProvider.get(getApplicationContext());
        Object eglContext = engine == null ? null : engine.videoEglContext();
        if (!(eglContext instanceof org.webrtc.EglBase.Context)) return;
        org.webrtc.EglBase.Context sharedContext = (org.webrtc.EglBase.Context) eglContext;
        remoteVideoView.init(sharedContext, null);
        remoteVideoView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        localVideoView.init(sharedContext, null);
        // SurfaceView needs an explicit overlay z-order to float above the remote surface.
        localVideoView.setZOrderMediaOverlay(true);
        localVideoView.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        renderersInitialized = true;
    }

    private void detachVideoSinks() {
        if (localVideoBound && localVideoTrack instanceof org.webrtc.VideoTrack
                && localVideoView != null) {
            ((org.webrtc.VideoTrack) localVideoTrack).removeSink(localVideoView);
        }
        if (remoteVideoBound && remoteVideoTrack instanceof org.webrtc.VideoTrack
                && remoteVideoView != null) {
            ((org.webrtc.VideoTrack) remoteVideoTrack).removeSink(remoteVideoView);
            ((org.webrtc.VideoTrack) remoteVideoTrack).removeSink(remoteFlowSink);
        }
        mainHandler.removeCallbacks(remoteFlowWatcher);
        remoteFlow.reset();
        remoteFrameBlack = false;
        remoteBlackCheckMs = 0L;
        localVideoBound = false;
        remoteVideoBound = false;
        localVideoTrack = null;
        remoteVideoTrack = null;
    }

    private void releaseVideoRenderers() {
        detachVideoSinks();
        if (remoteVideoView != null) remoteVideoView.release();
        if (localVideoView != null) localVideoView.release();
        renderersInitialized = false;
    }

    private static boolean granted(int[] grantResults) {
        return grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingSuperCall")
    @Override public void onBackPressed() {
        if (currentSession == null) {
            finish();
            return;
        }
        CallState state = currentSession.getState();
        if (state == CallState.OUTGOING_RINGING || state == CallState.INCOMING_RINGING) return;
        if (state == CallState.CONNECTING || state == CallState.ACTIVE
                || state == CallState.RECONNECTING_NETWORK) {
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }
}
