package com.nexachat.call.media;

import android.content.Context;
import com.nexachat.call.core.CallMediaEngine;

/** Process-wide audio engine, with a factory seam for JVM tests and native-library fallback. */
public final class CallMediaEngineProvider {
    public interface Factory {
        CallMediaEngine create(Context applicationContext);
    }

    private static CallMediaEngine engine;

    private CallMediaEngineProvider() { }

    public static synchronized CallMediaEngine get(Context context) {
        if (engine == null) {
            engine = create(context, WebRtcMediaEngine::new);
        }
        return engine;
    }

    static CallMediaEngine create(Context context, Factory factory) {
        if (factory == null) return new NoopMediaEngine();
        Context applicationContext = context == null ? null : context.getApplicationContext();
        try {
            CallMediaEngine created = factory.create(applicationContext);
            return created == null ? new NoopMediaEngine() : created;
        } catch (LinkageError | RuntimeException exception) {
            return new NoopMediaEngine();
        }
    }
}
