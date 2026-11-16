package com.nexachat.feature.media;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import com.google.android.material.snackbar.Snackbar;
import com.nexachat.R;
import java.io.File;

/** Creates safe read-only intents for app-owned attachment files (cache downloads). */
public final class AttachmentViewBinder {

    private AttachmentViewBinder() {
    }

    /**
     * {@link Intent#ACTION_VIEW} with a {@link FileProvider} read grant for a cached attachment.
     */
    @NonNull
    public static Intent createViewIntent(
            @NonNull Context context,
            @NonNull File file,
            @NonNull String mimeType) {
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file);
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /**
     * {@link Intent#ACTION_SEND} chooser payload for sharing a cached attachment.
     */
    @NonNull
    public static Intent createShareIntent(
            @NonNull Context context,
            @NonNull File file,
            @NonNull String mimeType) {
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                file);
        return new Intent(Intent.ACTION_SEND)
                .setType(mimeType)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    /** Starts {@link #createViewIntent} and anchors a snackbar on {@code anchor} if nothing handles it. */
    public static void openExternally(
            @NonNull View anchor,
            @NonNull File file,
            @NonNull String mimeType) {
        Context context = anchor.getContext();
        Intent intent = Intent.createChooser(
                createViewIntent(context, file, mimeType),
                context.getString(R.string.action_view));
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            Snackbar.make(anchor, R.string.error_attachment_open, Snackbar.LENGTH_SHORT).show();
        }
    }
}
