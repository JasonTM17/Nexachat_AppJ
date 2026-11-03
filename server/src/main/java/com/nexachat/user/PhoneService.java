package com.nexachat.user;

import com.nexachat.common.PhoneNormalizer;
import org.springframework.stereotype.Service;

/**
 * E.164 phone normalization boundary (plan T18).
 *
 * <p>Thin injectable adapter over the shared {@link PhoneNormalizer} utility —
 * callers receive a bean they can mock while the parsing logic itself lives in
 * one place. The contract matches the utility: input may be local format
 * ("0912345678") or international ("+84912345678"); output is always strict
 * E.164; anything unparseable or not a real number raises
 * {@link IllegalArgumentException}. Callers translate it into the API error that
 * fits their context (400 {@code VALIDATION_FAILED} on register, 401
 * {@code INVALID_CREDENTIALS} on login to avoid phone enumeration).
 */
@Service
public class PhoneService {

    /** Default region for numbers without a country calling code (VN per spec). */
    public static final String DEFAULT_REGION = PhoneNormalizer.DEFAULT_REGION;

    /**
     * @param raw user-supplied phone string, any format; VN is the default region
     * @return E.164 form, e.g. {@code +84912345678}
     * @throws IllegalArgumentException if the input cannot be parsed or is not a valid number
     */
    public String toE164(String raw) {
        return PhoneNormalizer.toE164(raw);
    }

    /**
     * @param raw           user-supplied phone string, any format
     * @param defaultRegion ISO region used when {@code raw} has no country code
     * @return E.164 form, e.g. {@code +84912345678}
     * @throws IllegalArgumentException if the input cannot be parsed or is not a valid number
     */
    public String toE164(String raw, String defaultRegion) {
        return PhoneNormalizer.toE164(raw, defaultRegion);
    }
}
