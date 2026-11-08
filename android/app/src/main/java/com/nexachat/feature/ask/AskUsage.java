package com.nexachat.feature.ask;

/** Transport metadata parsed for contract compatibility and intentionally not displayed. */
public final class AskUsage {
    private final int llmCalls;
    private final int inputChars;
    private final boolean cached;

    AskUsage() {
        this(0, 0, false);
    }

    public AskUsage(int llmCalls, int inputChars, boolean cached) {
        this.llmCalls = llmCalls;
        this.inputChars = inputChars;
        this.cached = cached;
    }

    public int getLlmCalls() {
        return llmCalls;
    }

    public int getInputChars() {
        return inputChars;
    }

    public boolean isCached() {
        return cached;
    }
}
