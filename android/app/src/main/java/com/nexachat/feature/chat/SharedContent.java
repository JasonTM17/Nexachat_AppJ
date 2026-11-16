package com.nexachat.feature.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/**
 * Single authority for the shared-content wire formats carried inside ordinary
 * TEXT messages. Location rides one line ({@code nexachat:loc:v1 <lat>,<lon>
 * [label]}), contact three ({@code nexachat:contact:v1}, name, phone). Parsing
 * is deliberately defensive: received content is untrusted, anything malformed
 * returns {@code null} and callers fall back to plain-text rendering.
 */
public final class SharedContent {

    public static final String LOCATION_PREFIX = "nexachat:loc:v1";
    public static final String CONTACT_PREFIX = "nexachat:contact:v1";
    /** Shared cap for a location label or a contact display name. */
    public static final int MAX_TEXT_CHARS = 80;
    private static final int MAX_E164_DIGITS = 15;

    /** Parsed shared payload; the wire format stays an implementation detail. */
    public sealed interface Shared permits Location, Contact {
    }

    /** A geo point with an optional short label. */
    public static final class Location implements Shared {
        private final double latitude;
        private final double longitude;
        @Nullable private final String label;

        public Location(double latitude, double longitude, @Nullable String label) {
            this.latitude = latitude;
            this.longitude = longitude;
            String clean = sanitizeText(label);
            this.label = clean.isEmpty() ? null : clean;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        @Nullable
        public String getLabel() {
            return label;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Location that
                    && latitude == that.latitude
                    && longitude == that.longitude
                    && Objects.equals(label, that.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(latitude, longitude, label);
        }
    }

    /** A picked contact card: display name plus an E.164 phone. */
    public static final class Contact implements Shared {
        private final String displayName;
        private final String phone;

        public Contact(@NonNull String displayName, @NonNull String phone) {
            this.displayName = sanitizeText(displayName);
            this.phone = sanitizePhone(phone);
        }

        @NonNull
        public String getDisplayName() {
            return displayName;
        }

        @NonNull
        public String getPhone() {
            return phone;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Contact that
                    && displayName.equals(that.displayName)
                    && phone.equals(that.phone);
        }

        @Override
        public int hashCode() {
            return Objects.hash(displayName, phone);
        }
    }

    private SharedContent() {
    }

    /**
     * Parses shared content out of message text. Only well-formed, complete
     * payloads parse; anything else (truncated, extra tokens, injected
     * newlines, out-of-range coordinates) yields {@code null}.
     */
    @Nullable
    public static Shared parse(@Nullable CharSequence text) {
        if (text == null) {
            return null;
        }
        String raw = text.toString();
        if (raw.startsWith(LOCATION_PREFIX)) {
            return parseLocation(raw);
        }
        if (raw.startsWith(CONTACT_PREFIX)) {
            return parseContact(raw);
        }
        return null;
    }

    /** Wire text for a location payload: {@code nexachat:loc:v1 lat,lon[ label]}. */
    @NonNull
    public static String encode(@NonNull Shared shared) {
        if (shared instanceof Location location) {
            return encodeLocation(location.getLatitude(), location.getLongitude(), location.getLabel());
        }
        Contact contact = (Contact) shared;
        return encodeContact(contact.getDisplayName(), contact.getPhone());
    }

    @NonNull
    public static String encodeLocation(double latitude, double longitude, @Nullable String label) {
        StringBuilder out = new StringBuilder(LOCATION_PREFIX)
                .append(' ')
                .append(formatCoordinate(latitude))
                .append(',')
                .append(formatCoordinate(longitude));
        String clean = sanitizeText(label);
        if (!clean.isEmpty()) {
            out.append(' ').append(clean);
        }
        return out.toString();
    }

    @NonNull
    public static String encodeContact(@NonNull String displayName, @NonNull String phone) {
        return CONTACT_PREFIX + '\n' + sanitizeText(displayName) + '\n' + sanitizePhone(phone);
    }

    /**
     * Neutral preview text for previews that lack resource access: the label
     * or "Location" for a geo point, "Contact: name" for a contact card.
     */
    @NonNull
    public static String previewText(@NonNull Shared shared) {
        if (shared instanceof Location location) {
            return location.getLabel() != null ? location.getLabel() : "Location";
        }
        return "Contact: " + ((Contact) shared).getDisplayName();
    }

    /** Human clipboard text for a shared payload; never the wire token. */
    @NonNull
    public static String copyText(@NonNull Shared shared) {
        if (shared instanceof Location location) {
            String coords = coordinates(location);
            return location.getLabel() != null
                    ? location.getLabel() + " (" + coords + ")"
                    : coords;
        }
        Contact contact = (Contact) shared;
        return contact.getDisplayName() + ", " + contact.getPhone();
    }

    /** Human text for {@code raw} when it parses as shared content, else {@code raw}. */
    @NonNull
    public static String copyText(@Nullable String raw) {
        Shared shared = parse(raw);
        return shared == null ? (raw == null ? "" : raw) : copyText(shared);
    }

    @NonNull
    public static String coordinates(@NonNull Location location) {
        return formatCoordinate(location.getLatitude())
                + ", " + formatCoordinate(location.getLongitude());
    }

    /** True when {@code value} is a plausible {@code +digits} E.164 phone. */
    public static boolean isE164(@Nullable String value) {
        if (value == null || value.length() < 2 || value.length() > MAX_E164_DIGITS + 1
                || value.charAt(0) != '+') {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Strips control characters, bidi overrides and marks that could spoof
     * display order, then trims and clamps to {@link #MAX_TEXT_CHARS}. Newlines
     * are removed so a label can never break the wire format.
     */
    @NonNull
    public static String sanitizeText(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || isBidiControl(c) || c == 0xFEFF
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) {
                continue;
            }
            out.append(c);
        }
        String clean = out.toString().trim();
        return clean.length() > MAX_TEXT_CHARS ? clean.substring(0, MAX_TEXT_CHARS) : clean;
    }

    /** Decimal degrees with at most 6 fraction digits and no trailing zeros. */
    @NonNull
    public static String formatCoordinate(double value) {
        double normalized = value == 0.0d ? 0.0d : value;
        String text = String.format(Locale.US, "%.6f", normalized);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    }

    @Nullable
    private static Shared parseLocation(@NonNull String raw) {
        // Format: "nexachat:loc:v1 <lat>,<lon>[ <label>]" on exactly one line.
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0
                || raw.length() <= LOCATION_PREFIX.length() + 1
                || raw.charAt(LOCATION_PREFIX.length()) != ' ') {
            return null;
        }
        String body = raw.substring(LOCATION_PREFIX.length() + 1);
        int split = body.indexOf(' ');
        String coords = split < 0 ? body : body.substring(0, split);
        String label = split < 0 ? "" : body.substring(split + 1);
        int comma = coords.indexOf(',');
        if (comma <= 0 || comma != coords.lastIndexOf(',') || comma == coords.length() - 1) {
            return null;
        }
        Double latitude = parseCoordinate(coords.substring(0, comma), 90.0d);
        Double longitude = parseCoordinate(coords.substring(comma + 1), 180.0d);
        if (latitude == null || longitude == null) {
            return null;
        }
        return new Location(latitude, longitude, label.isBlank() ? null : label);
    }

    @Nullable
    private static Double parseCoordinate(@NonNull String token, double maxAbs) {
        double value;
        try {
            value = Double.parseDouble(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
        // NaN and infinities are out-of-range by definition.
        if (Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) > maxAbs) {
            return null;
        }
        return value;
    }

    @Nullable
    private static Shared parseContact(@NonNull String raw) {
        // Format: "nexachat:contact:v1\n<display name>\n<+E.164>" — exactly 3 lines.
        String[] lines = raw.split("\n", -1);
        if (lines.length != 3 || !lines[0].equals(CONTACT_PREFIX)) {
            return null;
        }
        String name = sanitizeText(lines[1]);
        String phone = lines[2].trim();
        if (name.isEmpty() || !isE164(phone)) {
            return null;
        }
        return new Contact(name, phone);
    }

    @NonNull
    private static String sanitizePhone(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '+' && out.length() == 0) {
                out.append(c);
            } else if (Character.isDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Unicode bidi overrides/isolates that reorder or hide surrounding text. */
    private static boolean isBidiControl(char c) {
        return c >= 0x202A && c <= 0x202E
                || c >= 0x2066 && c <= 0x2069
                || c == 0x200E || c == 0x200F;
    }
}
