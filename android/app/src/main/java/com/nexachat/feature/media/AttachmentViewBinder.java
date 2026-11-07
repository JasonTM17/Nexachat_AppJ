package com.nexachat.feature.media;

import android.content.Context; import android.content.Intent; import android.net.Uri; import androidx.annotation.NonNull; import androidx.core.content.FileProvider;

/** Creates safe read-only ACTION_VIEW intents for app-owned attachment files. */
public final class AttachmentViewBinder {
    private AttachmentViewBinder() {}
    @NonNull public static Intent createViewIntent(@NonNull Context context,@NonNull java.io.File file,@NonNull String mimeType){Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".fileprovider",file);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);return intent;}
}
