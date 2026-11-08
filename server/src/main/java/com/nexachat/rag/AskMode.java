package com.nexachat.rag;

/** How the ask endpoint produced its answer. */
public enum AskMode {
    CACHE,
    NOT_FOUND,
    EXTRACTIVE,
    LLM
}
