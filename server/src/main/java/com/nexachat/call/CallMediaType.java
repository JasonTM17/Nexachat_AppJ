package com.nexachat.call;

/**
 * Media kind negotiated for a call. The offer carries it on INVITE; an omitted
 * value means AUDIO so pre-media-type clients keep working unchanged.
 */
public enum CallMediaType {
    AUDIO,
    VIDEO;

    public static CallMediaType orAudio(CallMediaType mediaType) {
        return mediaType == null ? AUDIO : mediaType;
    }
}
