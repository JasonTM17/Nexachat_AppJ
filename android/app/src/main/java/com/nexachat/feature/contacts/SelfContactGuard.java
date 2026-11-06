package com.nexachat.feature.contacts;

/**
 * Decides whether an add-by-phone attempt targets the signed-in account.
 * Comparison uses the same E.164 normalization as the contact request.
 */
public final class SelfContactGuard {

    public static final String ERROR_CODE = "CANNOT_ADD_SELF";

    private SelfContactGuard() {
    }

    public static boolean isOwnPhone(String candidate, String ownPhone) {
        if (candidate == null || ownPhone == null) {
            return false;
        }
        String left = com.nexachat.core.common.PhoneNormalizer.toE164(candidate);
        String right = com.nexachat.core.common.PhoneNormalizer.toE164(ownPhone);
        return !left.isEmpty() && left.equals(right);
    }
}
