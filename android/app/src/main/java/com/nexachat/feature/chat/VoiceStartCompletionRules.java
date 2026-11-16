package com.nexachat.feature.chat;

/**
 * Decides whether a {@code VoiceRecorder.start()} completion still belongs to
 * the live attempt. Each submitted start bumps a generation and teardown bumps
 * it again, so a completion posted by an older generation must not clear flags
 * owned by a newer one.
 */
final class VoiceStartCompletionRules {

    private VoiceStartCompletionRules() {}

    /** The completion is stale once anything past its attempt has bumped the generation. */
    static boolean isStale(long submittedGeneration, long currentGeneration) {
        return submittedGeneration != currentGeneration;
    }
}
