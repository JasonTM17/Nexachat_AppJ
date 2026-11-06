package com.nexachat.core.common;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Client-side E.164 normalization (libphonenumber, default region VN).
 *
 * Best-effort only: if parsing fails we return the trimmed raw input and let
 * the server's own normalization/validation be the authority — the client must
 * never crash on a weird phone string.
 */
public final class PhoneNormalizer {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNormalizer() {
    }

    public static String toE164(String raw) {
        return toE164(raw, "VN");
    }

    public static String toE164(String raw, String defaultRegion) {
        String trimmed = WhitespaceTrim.trim(raw);
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        try {
            PhoneNumber number = UTIL.parse(trimmed, defaultRegion);
            if (!UTIL.isValidNumber(number)) {
                return trimmed;
            }
            return UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException ignored) {
            return trimmed;
        } catch (RuntimeException ignored) {
            // e.g. invalid region metadata — stay honest, send raw
            return trimmed;
        }
    }
}
