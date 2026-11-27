package com.nexachat.call.media;

import org.junit.Test;
import org.webrtc.PeerConnection;

import static org.junit.Assert.assertEquals;

public class WebRtcPeerConfigurationTest {
    @Test
    public void gathersCandidatesWhenTheActiveNetworkChanges() {
        PeerConnection.RTCConfiguration configuration = WebRtcMediaEngine.peerConfiguration(null);

        assertEquals(PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY,
                configuration.continualGatheringPolicy);
    }
}
