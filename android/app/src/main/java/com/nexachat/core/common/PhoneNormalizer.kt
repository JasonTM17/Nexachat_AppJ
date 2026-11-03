package com.nexachat.core.common

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber

/**
 * Client-side E.164 normalization (libphonenumber, default region VN).
 *
 * Best-effort only: if parsing fails we return the trimmed raw input and let
 * the server's own normalization/validation be the authority — the client must
 * never crash on a weird phone string.
 */
object PhoneNormalizer {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    fun toE164(raw: String, defaultRegion: String = "VN"): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return try {
            val number: PhoneNumber = util.parse(trimmed, defaultRegion)
            if (!util.isValidNumber(number)) return trimmed
            util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (_: NumberParseException) {
            trimmed
        } catch (_: RuntimeException) {
            // e.g. invalid region metadata — stay honest, send raw
            trimmed
        }
    }
}
