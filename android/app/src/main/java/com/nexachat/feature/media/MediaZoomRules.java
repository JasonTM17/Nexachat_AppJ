package com.nexachat.feature.media;

/** Bounded image geometry shared by touch and accessibility zoom controls. */
public final class MediaZoomRules {
    private MediaZoomRules() { }
    public static float clampZoom(float zoom) {
        return Float.isFinite(zoom) ? Math.max(1f, Math.min(4f, zoom)) : 1f;
    }
    public static float clampPan(float pan, float content, float viewport) {
        float limit = Math.max(0f, (content - viewport) / 2f);
        return Float.isFinite(pan) ? Math.max(-limit, Math.min(limit, pan)) : 0f;
    }
    public static boolean shouldDismiss(float zoom, float dx, float dy, float threshold) {
        return zoom <= 1f && Math.abs(dy) >= threshold && Math.abs(dy) > Math.abs(dx) * 1.5f;
    }
}
