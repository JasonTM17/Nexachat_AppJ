package com.nexachat.call.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.nexachat.call.core.CallMediaEngine;
import com.nexachat.call.core.CallSignal;
import com.nexachat.call.core.IceConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

/** Audio-only WebRTC engine. Native peer resources and callbacks use one serialized executor. */
public final class WebRtcMediaEngine implements CallMediaEngine {
    private static final String AUDIO_STREAM_ID = "nexachat-audio";
    private static final String AUDIO_TRACK_ID = "nexachat-audio-track";
    private static final Object FACTORY_LOCK = new Object();
    private static PeerConnectionFactory sharedFactory;
    @SuppressWarnings("unused")
    private static AudioDeviceModule sharedAudioDeviceModule;

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService mediaExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "nexachat-webrtc");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();

    private volatile boolean muted;
    private volatile boolean speakerOn;
    private volatile Listener listener;
    private PeerConnection peerConnection;
    private AudioSource localAudioSource;
    private AudioTrack localAudioTrack;
    private long activeGeneration;
    private long failedGeneration = -1L;
    private boolean remoteDescriptionSet;
    private boolean connectedReported;
    private boolean audioModeCaptured;
    private int previousAudioMode;
    private boolean previousSpeakerphoneOn;
    private AudioDeviceInfo previousCommunicationDevice;
    private boolean audioFocusHeld;
    private AudioFocusRequest audioFocusRequest;
    private AudioManager.OnAudioFocusChangeListener legacyAudioFocusListener;
    private PeerConnection.IceConnectionState lastIceState = PeerConnection.IceConnectionState.NEW;
    private ScheduledFuture<?> disconnectTimeout;
    private ScheduledFuture<?> connectTimeout;
    private final List<IceCandidate> pendingRemoteIce = new ArrayList<>();

    public WebRtcMediaEngine(Context context) {
        appContext = context.getApplicationContext();
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void start(boolean isCaller, IceConfig iceConfig, Listener callListener) {
        long callGeneration = generation.incrementAndGet();
        mediaExecutor.execute(() -> {
            if (!isCurrent(callGeneration)) return;
            releasePeerResources();
            activeGeneration = callGeneration;
            listener = callListener;
            failedGeneration = -1L;
            connectedReported = false;
            remoteDescriptionSet = false;
            pendingRemoteIce.clear();
            lastIceState = PeerConnection.IceConnectionState.NEW;
            try {
                PeerConnectionFactory factory = getFactory();
                enterCommunicationMode();
                PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(
                        toRtcIceServers(iceConfig));
                configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
                peerConnection = factory.createPeerConnection(configuration, new PeerObserver(callGeneration));
                if (peerConnection == null) throw new IllegalStateException("Peer connection unavailable");

                MediaConstraints audioConstraints = new MediaConstraints();
                audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                        "googEchoCancellation", "true"));
                audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                        "googNoiseSuppression", "true"));
                audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(
                        "googAutoGainControl", "true"));
                localAudioSource = factory.createAudioSource(audioConstraints);
                localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, localAudioSource);
                localAudioTrack.setEnabled(!muted);
                peerConnection.addTrack(localAudioTrack, Collections.singletonList(AUDIO_STREAM_ID));
                applySpeakerRoute();
                connectTimeout = mediaExecutor.schedule(() -> {
                    if (isActive(callGeneration) && !connectedReported) reportFailure(callGeneration);
                }, 35L, TimeUnit.SECONDS);
                if (isCaller) createDescription(callGeneration, true);
            } catch (RuntimeException | LinkageError exception) {
                reportFailure(callGeneration);
                releasePeerResources();
            }
        });
    }

    @Override
    public void onRemoteSdp(CallSignal.Sdp sdp) {
        if (sdp == null || sdp.getType() == null || sdp.getDescription() == null) return;
        long callGeneration = generation.get();
        mediaExecutor.execute(() -> {
            if (!isActive(callGeneration) || peerConnection == null) return;
            final SessionDescription remoteDescription;
            try {
                remoteDescription = new SessionDescription(
                        SessionDescription.Type.fromCanonicalForm(sdp.getType()), sdp.getDescription());
            } catch (RuntimeException exception) {
                reportFailure(callGeneration);
                return;
            }
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription description) { }

                @Override public void onSetSuccess() {
                    mediaExecutor.execute(() -> {
                        if (!isActive(callGeneration)) return;
                        remoteDescriptionSet = true;
                        flushRemoteIce();
                        if (remoteDescription.type == SessionDescription.Type.OFFER) {
                            createDescription(callGeneration, false);
                        }
                    });
                }

                @Override public void onCreateFailure(String error) {
                    postFailure(callGeneration);
                }

                @Override public void onSetFailure(String error) {
                    postFailure(callGeneration);
                }
            }, remoteDescription);
        });
    }

    @Override
    public void onRemoteIce(CallSignal.IceCandidate candidate) {
        if (candidate == null || candidate.getCandidate() == null) return;
        long callGeneration = generation.get();
        mediaExecutor.execute(() -> {
            if (!isActive(callGeneration) || peerConnection == null) return;
            Integer lineIndex = candidate.getSdpMLineIndex();
            IceCandidate iceCandidate = new IceCandidate(
                    candidate.getSdpMid(), lineIndex == null ? 0 : lineIndex, candidate.getCandidate());
            if (!remoteDescriptionSet) {
                pendingRemoteIce.add(iceCandidate);
            } else if (!peerConnection.addIceCandidate(iceCandidate)) {
                reportFailure(callGeneration);
            }
        });
    }

    @Override
    public void setMuted(boolean muted) {
        this.muted = muted;
        long callGeneration = generation.get();
        mediaExecutor.execute(() -> {
            if (isCurrent(callGeneration) && localAudioTrack != null) {
                localAudioTrack.setEnabled(!muted);
            }
        });
    }

    @Override
    public void setSpeaker(boolean speakerOn) {
        this.speakerOn = speakerOn;
        long callGeneration = generation.get();
        mediaExecutor.execute(() -> {
            if (isCurrent(callGeneration) && audioModeCaptured) applySpeakerRoute();
        });
    }

    @Override
    public void stop() {
        long callGeneration = generation.incrementAndGet();
        try {
            mediaExecutor.execute(() -> {
                if (callGeneration != generation.get()) return;
                activeGeneration = callGeneration;
                listener = null;
                releasePeerResources();
            });
        } catch (RuntimeException ignored) {
            // The process is shutting down; all native resources are owned by this process.
        }
    }

    private PeerConnectionFactory getFactory() {
        synchronized (FACTORY_LOCK) {
            if (sharedFactory != null) return sharedFactory;
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                    .builder(appContext)
                    .createInitializationOptions());
            JavaAudioDeviceModule audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule();
            sharedAudioDeviceModule = audioDeviceModule;
            sharedFactory = PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory();
            if (sharedFactory == null) throw new IllegalStateException("WebRTC factory unavailable");
            return sharedFactory;
        }
    }

    private static List<PeerConnection.IceServer> toRtcIceServers(IceConfig config) {
        List<PeerConnection.IceServer> result = new ArrayList<>();
        if (config == null) return result;
        for (IceConfig.IceServer server : config.getIceServers()) {
            for (String url : server.getUrls()) {
                if (url == null || url.trim().isEmpty()) continue;
                PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(url);
                if (server.getUsername() != null) builder.setUsername(server.getUsername());
                if (server.getCredential() != null) builder.setPassword(server.getCredential());
                result.add(builder.createIceServer());
            }
        }
        return result;
    }

    private void createDescription(long callGeneration, boolean offer) {
        if (!isActive(callGeneration) || peerConnection == null) return;
        MediaConstraints constraints = new MediaConstraints();
        SdpObserver observer = new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription description) {
                mediaExecutor.execute(() -> {
                    if (!isActive(callGeneration) || peerConnection == null) return;
                    peerConnection.setLocalDescription(new SdpObserver() {
                        @Override public void onCreateSuccess(SessionDescription ignored) { }

                        @Override public void onSetSuccess() {
                            if (!isActive(callGeneration)) return;
                            Listener current = listener;
                            if (current != null) {
                                postToMain(callGeneration, current, () -> current.onLocalSdp(
                                        new CallSignal.Sdp(
                                                description.type.canonicalForm(), description.description)));
                            }
                        }

                        @Override public void onCreateFailure(String error) {
                            postFailure(callGeneration);
                        }

                        @Override public void onSetFailure(String error) {
                            postFailure(callGeneration);
                        }
                    }, description);
                });
            }

            @Override public void onSetSuccess() { }

            @Override public void onCreateFailure(String error) {
                postFailure(callGeneration);
            }

            @Override public void onSetFailure(String error) {
                postFailure(callGeneration);
            }
        };
        if (offer) peerConnection.createOffer(observer, constraints);
        else peerConnection.createAnswer(observer, constraints);
    }

    private void flushRemoteIce() {
        if (peerConnection == null) return;
        for (IceCandidate candidate : pendingRemoteIce) {
            if (!peerConnection.addIceCandidate(candidate)) reportFailure(activeGeneration);
        }
        pendingRemoteIce.clear();
    }

    @SuppressWarnings("deprecation")
    private void enterCommunicationMode() {
        if (audioManager == null || audioModeCaptured) return;
        previousAudioMode = audioManager.getMode();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            previousCommunicationDevice = audioManager.getCommunicationDevice();
        } else {
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn();
        }
        audioModeCaptured = true;
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(change -> { })
                    .build();
            audioFocusHeld = audioManager.requestAudioFocus(audioFocusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            legacyAudioFocusListener = change -> { };
            audioFocusHeld = audioManager.requestAudioFocus(legacyAudioFocusListener,
                    AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void applySpeakerRoute() {
        if (audioManager == null) return;
        CallAudioRoutePolicy.Route route = CallAudioRoutePolicy.routeFor(speakerOn);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int targetType = route == CallAudioRoutePolicy.Route.SPEAKER
                    ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                if (device.getType() == targetType) {
                    audioManager.setCommunicationDevice(device);
                    return;
                }
            }
        } else {
            setLegacySpeakerRoute(route == CallAudioRoutePolicy.Route.SPEAKER);
        }
    }

    @SuppressWarnings("deprecation")
    private void setLegacySpeakerRoute(boolean enabled) {
        audioManager.setSpeakerphoneOn(enabled);
    }

    @SuppressWarnings("deprecation")
    private void restoreAudioMode() {
        if (audioManager == null || !audioModeCaptured) return;
        try {
            if (audioFocusHeld) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                    audioManager.abandonAudioFocusRequest(audioFocusRequest);
                } else {
                    audioManager.abandonAudioFocus(legacyAudioFocusListener);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (previousCommunicationDevice == null) audioManager.clearCommunicationDevice();
                else audioManager.setCommunicationDevice(previousCommunicationDevice);
                previousCommunicationDevice = null;
            } else {
                restoreLegacySpeakerRoute();
            }
            audioManager.setMode(previousAudioMode);
        } catch (RuntimeException ignored) {
            // Audio routing may already have been reclaimed by the system.
        } finally {
            audioFocusHeld = false;
            audioFocusRequest = null;
            legacyAudioFocusListener = null;
            audioModeCaptured = false;
        }
    }

    @SuppressWarnings("deprecation")
    private void restoreLegacySpeakerRoute() {
        audioManager.setSpeakerphoneOn(previousSpeakerphoneOn);
    }

    private void releasePeerResources() {
        if (disconnectTimeout != null) disconnectTimeout.cancel(false);
        disconnectTimeout = null;
        if (connectTimeout != null) connectTimeout.cancel(false);
        connectTimeout = null;
        if (peerConnection != null) {
            try {
                peerConnection.close();
                peerConnection.dispose();
            } catch (RuntimeException ignored) {
                // Continue disposing tracks and restoring the system audio route.
            }
            peerConnection = null;
        }
        if (localAudioTrack != null) {
            localAudioTrack.dispose();
            localAudioTrack = null;
        }
        if (localAudioSource != null) {
            localAudioSource.dispose();
            localAudioSource = null;
        }
        pendingRemoteIce.clear();
        remoteDescriptionSet = false;
        restoreAudioMode();
    }

    private void reportFailure(long callGeneration) {
        if (!isCurrent(callGeneration) || failedGeneration == callGeneration) return;
        failedGeneration = callGeneration;
        if (connectTimeout != null) {
            connectTimeout.cancel(false);
            connectTimeout = null;
        }
        Listener current = listener;
        if (current != null) {
            postToMain(callGeneration, current, () -> current.onFailed("WebRTC call failed"));
        }
    }

    private void postFailure(long callGeneration) {
        try {
            mediaExecutor.execute(() -> reportFailure(callGeneration));
        } catch (RuntimeException ignored) {
            // A late native callback after process shutdown has no live UI to notify.
        }
    }

    private void reportConnected(long callGeneration) {
        if (!isActive(callGeneration) || connectedReported) return;
        connectedReported = true;
        if (connectTimeout != null) {
            connectTimeout.cancel(false);
            connectTimeout = null;
        }
        Listener current = listener;
        if (current != null) postToMain(callGeneration, current, current::onConnected);
    }

    private void postToMain(long callGeneration, Listener target, Runnable callback) {
        mainHandler.post(() -> {
            if (generation.get() == callGeneration && listener == target) callback.run();
        });
    }

    private boolean isCurrent(long callGeneration) {
        return generation.get() == callGeneration;
    }

    private boolean isActive(long callGeneration) {
        return isCurrent(callGeneration) && activeGeneration == callGeneration;
    }

    private final class PeerObserver implements PeerConnection.Observer {
        private final long callGeneration;

        private PeerObserver(long callGeneration) {
            this.callGeneration = callGeneration;
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState state) { }

        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            try {
                mediaExecutor.execute(() -> {
                    if (!isActive(callGeneration)) return;
                    lastIceState = state;
                    if (disconnectTimeout != null) {
                        disconnectTimeout.cancel(false);
                        disconnectTimeout = null;
                    }
                    if (state == PeerConnection.IceConnectionState.CONNECTED
                            || state == PeerConnection.IceConnectionState.COMPLETED) {
                        reportConnected(callGeneration);
                    } else if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
                        disconnectTimeout = mediaExecutor.schedule(() -> {
                            if (isActive(callGeneration)
                                    && lastIceState == PeerConnection.IceConnectionState.DISCONNECTED) {
                                reportFailure(callGeneration);
                            }
                        }, 5L, TimeUnit.SECONDS);
                    } else if (state == PeerConnection.IceConnectionState.FAILED) {
                        reportFailure(callGeneration);
                    }
                });
            } catch (RuntimeException ignored) {
                // Executor is shutting down with the application process.
            }
        }

        @Override public void onIceConnectionReceivingChange(boolean receiving) { }

        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) { }

        @Override public void onIceCandidate(IceCandidate candidate) {
            if (candidate == null) return;
            CallSignal.IceCandidate converted = new CallSignal.IceCandidate(
                    candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp);
            Listener current = listener;
            if (current != null) {
                postToMain(callGeneration, current, () -> current.onLocalIce(converted));
            }
        }

        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) { }

        @Override public void onAddStream(org.webrtc.MediaStream stream) { }

        @Override public void onRemoveStream(org.webrtc.MediaStream stream) { }

        @Override public void onTrack(RtpTransceiver transceiver) {
            if (transceiver == null || transceiver.getReceiver() == null) return;
            if (transceiver.getReceiver().track() instanceof AudioTrack) {
                transceiver.getReceiver().track().setEnabled(true);
            }
        }

        @Override public void onDataChannel(org.webrtc.DataChannel dataChannel) { }

        @Override public void onRenegotiationNeeded() { }
    }
}
