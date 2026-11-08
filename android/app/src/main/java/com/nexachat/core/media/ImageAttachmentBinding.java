package com.nexachat.core.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Small view-binding seam for immediate placeholders and RecyclerView request identity. */
public final class ImageAttachmentBinding<P> {

    public Object bind(
            @Nullable String url,
            @Nullable P placeholder,
            @NonNull Target<P> target,
            @NonNull Loader<P> loader) {
        target.clearRequest();
        Object token = new Object();
        target.setRequestToken(token);
        target.showPlaceholder(placeholder);
        if (!MediaText.isBlank(url)) {
            loader.load(url, placeholder, token, target);
        }
        return token;
    }

    public static boolean isCurrent(
            @NonNull Target<?> target,
            @NonNull Object requestToken) {
        return target.requestToken() == requestToken;
    }

    public interface Target<P> {
        void clearRequest();

        void setRequestToken(@NonNull Object requestToken);

        @Nullable Object requestToken();

        void showPlaceholder(@Nullable P placeholder);
    }

    @FunctionalInterface
    public interface Loader<P> {
        void load(
                @NonNull String url,
                @Nullable P placeholder,
                @NonNull Object requestToken,
                @NonNull Target<P> target);
    }
}
