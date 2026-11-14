package com.nexachat.e2ee.dto;

import java.util.List;

/** E2EE state of a DIRECT conversation plus the active devices of its members. */
public record ConversationE2eeStateResponse(boolean enabled, List<E2eeDeviceView> devices) {
}
