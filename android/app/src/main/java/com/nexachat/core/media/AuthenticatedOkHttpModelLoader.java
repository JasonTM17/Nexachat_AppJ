package com.nexachat.core.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;
import com.nexachat.core.common.AppConfig;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Routes remote Glide URLs through the authenticated and media-cache-scoped OkHttp client. */
public final class AuthenticatedOkHttpModelLoader implements ModelLoader<String, InputStream> {

    private final OkHttpClient client;
    private final HttpUrl apiBaseUrl;

    public AuthenticatedOkHttpModelLoader(@NonNull OkHttpClient client) {
        this(client, HttpUrl.get(AppConfig.API_BASE_URL));
    }

    AuthenticatedOkHttpModelLoader(@NonNull OkHttpClient client, @NonNull HttpUrl apiBaseUrl) {
        this.client = Objects.requireNonNull(client, "client");
        this.apiBaseUrl = Objects.requireNonNull(apiBaseUrl, "apiBaseUrl");
    }

    @Override
    public boolean handles(@NonNull String model) {
        HttpUrl url = HttpUrl.parse(model);
        return url != null
                && url.scheme().equals(apiBaseUrl.scheme())
                && url.host().equals(apiBaseUrl.host())
                && url.port() == apiBaseUrl.port();
    }

    @Nullable
    @Override
    public LoadData<InputStream> buildLoadData(
            @NonNull String model,
            int width,
            int height,
            @NonNull Options options) {
        if (!handles(model)) {
            return null;
        }
        return new LoadData<>(new ObjectKey(model), new Fetcher(client, model));
    }

    public static final class Factory implements ModelLoaderFactory<String, InputStream> {
        private final OkHttpClient client;

        public Factory(@NonNull OkHttpClient client) {
            this.client = Objects.requireNonNull(client, "client");
        }

        @NonNull
        @Override
        public ModelLoader<String, InputStream> build(@NonNull MultiModelLoaderFactory multiFactory) {
            return new AuthenticatedOkHttpModelLoader(client);
        }

        @Override
        public void teardown() { }
    }

    private static final class Fetcher implements DataFetcher<InputStream> {
        private final OkHttpClient client;
        private final String model;
        private volatile Call call;
        private volatile Response response;
        private volatile boolean cancelled;

        private Fetcher(OkHttpClient client, String model) {
            this.client = client;
            this.model = model;
        }

        @Override
        public void loadData(
                @NonNull Priority priority,
                @NonNull DataCallback<? super InputStream> callback) {
            if (cancelled) {
                return;
            }
            call = client.newCall(new Request.Builder().url(model).get().build());
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call failedCall, @NonNull IOException error) {
                    if (!cancelled) {
                        callback.onLoadFailed(error);
                    }
                }

                @Override
                public void onResponse(@NonNull Call completedCall, @NonNull Response result) {
                    if (cancelled) {
                        result.close();
                        return;
                    }
                    if (!result.isSuccessful()) {
                        int status = result.code();
                        result.close();
                        callback.onLoadFailed(new IOException("media request failed: " + status));
                        return;
                    }
                    ResponseBody body = result.body();
                    if (body == null) {
                        result.close();
                        callback.onLoadFailed(new IOException("media response has no body"));
                        return;
                    }
                    response = result;
                    callback.onDataReady(body.byteStream());
                }
            });
        }

        @Override
        public void cleanup() {
            Response active = response;
            response = null;
            if (active != null) {
                active.close();
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            Call active = call;
            if (active != null) {
                active.cancel();
            }
        }

        @NonNull
        @Override
        public Class<InputStream> getDataClass() {
            return InputStream.class;
        }

        @NonNull
        @Override
        public DataSource getDataSource() {
            return DataSource.REMOTE;
        }
    }
}
