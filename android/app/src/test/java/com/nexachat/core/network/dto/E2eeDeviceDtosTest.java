package com.nexachat.core.network.dto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.squareup.moshi.Moshi;
import java.util.List;
import org.junit.Test;

/**
 * Real-Moshi fixtures for the experimental v1 activation capability: absent
 * and {@code null} both mean not allowed, the flag is independent of
 * {@code enabled}, and unknown response fields stay tolerated.
 */
public final class E2eeDeviceDtosTest {

    private final Moshi moshi = new Moshi.Builder().build();

    private ConversationE2eeDto parse(String json) throws Exception {
        ConversationE2eeDto dto = moshi.adapter(ConversationE2eeDto.class).fromJson(json);
        assert dto != null;
        return dto;
    }

    @Test
    public void absentCapabilityMeansNotAllowed() throws Exception {
        ConversationE2eeDto dto = parse("{\"enabled\":true,\"devices\":[]}");
        assertTrue(dto.isEnabled());
        assertFalse(dto.isExperimentalV1ActivationAllowed());
    }

    @Test
    public void nullCapabilityMeansNotAllowed() throws Exception {
        ConversationE2eeDto dto = parse(
                "{\"enabled\":true,\"devices\":[],\"experimentalV1ActivationAllowed\":null}");
        assertFalse(dto.isExperimentalV1ActivationAllowed());
    }

    @Test
    public void falseCapabilityMeansNotAllowed() throws Exception {
        ConversationE2eeDto dto = parse(
                "{\"enabled\":true,\"devices\":[],\"experimentalV1ActivationAllowed\":false}");
        assertFalse(dto.isExperimentalV1ActivationAllowed());
    }

    @Test
    public void trueCapabilityMeansAllowed() throws Exception {
        ConversationE2eeDto dto = parse(
                "{\"enabled\":true,\"devices\":[],\"experimentalV1ActivationAllowed\":true}");
        assertTrue(dto.isExperimentalV1ActivationAllowed());
    }

    @Test
    public void enabledStaysIndependentOfCapability() throws Exception {
        ConversationE2eeDto off = parse(
                "{\"enabled\":false,\"devices\":[],\"experimentalV1ActivationAllowed\":true}");
        assertFalse(off.isEnabled());
        assertTrue(off.isExperimentalV1ActivationAllowed());

        ConversationE2eeDto on = parse(
                "{\"enabled\":true,\"devices\":[],\"experimentalV1ActivationAllowed\":false}");
        assertTrue(on.isEnabled());
        assertFalse(on.isExperimentalV1ActivationAllowed());
    }

    @Test
    public void unknownFieldsAreTolerated() throws Exception {
        ConversationE2eeDto dto = parse(
                "{\"enabled\":false,\"devices\":[],\"futureField\":{\"a\":1},"
                        + "\"experimentalV1ActivationAllowed\":true}");
        assertTrue(dto.isExperimentalV1ActivationAllowed());
    }

    @Test
    public void twoArgumentConstructorDefaultsCapabilityToNotAllowed() {
        ConversationE2eeDto dto = new ConversationE2eeDto(true, List.of());
        assertTrue(dto.isEnabled());
        assertFalse(dto.isExperimentalV1ActivationAllowed());
    }
}
