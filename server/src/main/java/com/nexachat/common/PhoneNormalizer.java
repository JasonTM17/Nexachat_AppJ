package com.nexachat.common;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * Normalizes phone numbers to E.164 using Google's {@code libphonenumber}.
 *
 * <p>E.164 is the ITU international public telecommunication numbering plan.
 * A number in E.164 consists of a leading {@code +}, the country calling code,
 * and the national subscriber number, with no spaces, dashes, parentheses, or
 * other separators, and at most 15 digits in total. Example:
 * {@code +84901234567} (Vietnam).</p>
 *
 * <p>The {@code defaultRegion} argument (an ISO 3166-1 alpha-2 code such as
 * {@code "VN"}) is only consulted when the input is in national format, i.e.
 * does not start with {@code +}. Inputs that already carry a country code
 * without a leading {@code +} are also understood when they match the default
 * region, so {@code "84 901 234 567"} with region {@code "VN"} normalizes to
 * {@code "+84901234567"}.</p>
 *
 * <p>This class is stateless and thread-safe: {@link PhoneNumberUtil} is
 * immutable after initialization and its parsing/formatting methods are safe
 * for concurrent use.</p>
 */
public final class PhoneNormalizer {

    /** ISO 3166-1 alpha-2 region used by the single-argument overloads. */
    public static final String DEFAULT_REGION = "VN";

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private PhoneNormalizer() {
        // Utility class: no instances.
    }

    /**
     * Parses {@code raw} and returns it formatted as E.164.
     *
     * @param raw           the phone number in national or international format;
     *                      spaces, dashes, dots and parentheses are ignored
     * @param defaultRegion ISO 3166-1 alpha-2 region used when {@code raw} has
     *                      no country code, e.g. {@code "VN"}
     * @return the number in E.164 format, e.g. {@code "+84901234567"}
     * @throws IllegalArgumentException if {@code raw} is {@code null}, blank,
     *                                  unparseable, or not a valid number
     */
    public static String toE164(String raw, String defaultRegion) {
        PhoneNumber number = parse(raw, defaultRegion);
        if (!UTIL.isValidNumber(number)) {
            throw new IllegalArgumentException(
                    "Invalid phone number for region " + defaultRegion + ": " + raw);
        }
        return UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
    }

    /**
     * Parses {@code raw} using {@value #DEFAULT_REGION} as the default region
     * and returns it formatted as E.164.
     *
     * @param raw the phone number in national or international format
     * @return the number in E.164 format
     * @throws IllegalArgumentException if {@code raw} is invalid
     */
    public static String toE164(String raw) {
        return toE164(raw, DEFAULT_REGION);
    }

    /**
     * Checks whether {@code raw} is a valid phone number for {@code region}.
     *
     * @param raw    the phone number to check; separators are ignored
     * @param region ISO 3166-1 alpha-2 region used when {@code raw} has no
     *               country code
     * @return {@code true} only when the number parses and is valid;
     *         {@code false} for {@code null}, blank, unparseable, or invalid input
     */
    public static boolean isValidPhone(String raw, String region) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            return UTIL.isValidNumber(parse(raw, region));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static PhoneNumber parse(String raw, String region) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Phone number must not be null or blank");
        }
        try {
            return UTIL.parse(raw, region);
        } catch (NumberParseException ex) {
            throw new IllegalArgumentException("Unparseable phone number: " + raw, ex);
        }
    }
}
