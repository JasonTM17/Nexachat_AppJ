package com.nexachat.feature.chat;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.ContactsContract;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.R;

/**
 * Localized presentation and outbound intents for shared location/contact
 * payloads. Received content stays sanitized upstream in
 * {@link SharedContent}; this layer only turns it into human text and system
 * intents, never crashing when no handler exists.
 */
public final class SharedContentUi {

    private SharedContentUi() {
    }

    /**
     * Human preview for {@code raw}: a localized title when it parses as
     * shared content, otherwise {@code raw} unchanged. The wire token never
     * surfaces in previews.
     */
    @NonNull
    public static String previewText(@NonNull Context context, @Nullable String raw) {
        SharedContent.Shared shared = SharedContent.parse(raw);
        if (shared == null) {
            return raw == null ? "" : raw;
        }
        return previewText(context, shared);
    }

    @NonNull
    public static String previewText(@NonNull Context context, @NonNull SharedContent.Shared shared) {
        String text;
        if (shared instanceof SharedContent.Location location) {
            text = location.getLabel() != null
                    ? location.getLabel()
                    : context.getString(R.string.shared_location_title);
        } else {
            text = context.getString(R.string.shared_contact_preview,
                    ((SharedContent.Contact) shared).getDisplayName());
        }
        // Mocked contexts resolve no resources; stay readable anyway.
        return text == null ? SharedContent.previewText(shared) : text;
    }

    /**
     * Opens a geo intent first (the user's map app wins); with no handler, an
     * OpenStreetMap browser link. Never throws — a device with neither simply
     * ignores the tap.
     */
    public static void openMap(@NonNull Context context, @NonNull SharedContent.Location location) {
        String latitude = SharedContent.formatCoordinate(location.getLatitude());
        String longitude = SharedContent.formatCoordinate(location.getLongitude());
        String query = location.getLabel() == null
                ? latitude + "," + longitude
                : latitude + "," + longitude + "(" + Uri.encode(location.getLabel()) + ")";
        Intent geo = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:" + latitude + "," + longitude + "?q=" + query));
        try {
            if (geo.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(geo);
                return;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the browser link below.
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.openstreetmap.org/?mlat=" + latitude + "&mlon=" + longitude
                            + "#map=16/" + latitude + "/" + longitude)));
        } catch (RuntimeException ignored) {
            // No map or browser on this device; the tap is a no-op.
        }
    }

    /** Saves a shared contact through the system insert flow. */
    public static boolean addContact(@NonNull Context context, @NonNull SharedContent.Contact contact) {
        Intent intent = new Intent(ContactsContract.Intents.Insert.ACTION)
                .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, contact.getDisplayName())
                .putExtra(ContactsContract.Intents.Insert.PHONE, contact.getPhone());
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
