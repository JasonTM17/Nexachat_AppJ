package com.nexachat.conversation.dto;

import java.util.UUID;

/** Current membership and effective mute for message push dispatch. */
public record PushRecipientRow(UUID userId, boolean muted) {}
