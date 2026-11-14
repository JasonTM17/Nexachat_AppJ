package com.nexachat.core.network;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

final class AiRequestTimeoutInterceptor implements Interceptor {
    // The default server chain can wait 30 s for each cloud model and 60 s for the local model.
    private static final int AI_READ_TIMEOUT_SECONDS = 130;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        if (isAiRequest(request.method(), request.url().encodedPath())) {
            return chain.withReadTimeout(AI_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).proceed(request);
        }
        return chain.proceed(request);
    }

    static boolean isAiRequest(String method, String encodedPath) {
        String[] path = encodedPath.split("/", -1);
        return "POST".equals(method)
                && path.length == 6
                && "api".equals(path[1])
                && "v1".equals(path[2])
                && "conversations".equals(path[3])
                && !path[4].isEmpty()
                && ("summary".equals(path[5]) || "ask".equals(path[5]));
    }
}
