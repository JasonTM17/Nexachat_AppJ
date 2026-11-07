package com.nexachat.core.common;

/**
 * Pure-Java unit value object for RxJava3 streams and {@link Result} payloads
 * where {@code Void} ({@code null}) is disallowed by Reactive Streams.
 */
public final class Unit {
    public static final Unit INSTANCE = new Unit();

    private Unit() {
    }

    @Override
    public String toString() {
        return "Unit";
    }
}
