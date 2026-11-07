package com.nexachat.feature.media;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
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

    /** Starts {@link #createViewIntent} and shows a snackbar-style toast if nothing handles it. */
    public static void openExternally(
            @NonNull Context context,
            @NonNull File file,
            @NonNull String mimeType) {
        Intent intent = Intent.createChooser(
                createViewIntent(context, file, mimeType),
                context.getString(R.string.action_view));
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            android.widget.Toast.makeText(
                    context,
                    R.string.error_attachment_open,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
