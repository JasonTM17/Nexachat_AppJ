package com.nexachat.core.ui.components

import java.net.URI

private val URL_REGEX = """(https?://[^\s<>"'{}|\\^`]+)""".toRegex(RegexOption.IGNORE_CASE)

/**
 * Pure UI presentation rule: strictly permits only http and https schemes.
 * Rejects javascript:, file:, content:, intent:, data:, etc. to prevent
 * arbitrary intent/scheme injection.
 * Returns sanitized string or null if invalid.
 */
fun sanitizeLinkUrl(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
        return null
    }
    return try {
        val parsed = URI(trimmed)
        val scheme = parsed.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            trimmed
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Pure UI presentation rule: extracts all valid http/https URLs with their character ranges in [text].
 */
fun extractHttpLinks(text: String): List<Pair<IntRange, String>> {
    if (text.isBlank()) return emptyList()
    return URL_REGEX.findAll(text).mapNotNull { match ->
        val url = match.value
        val sanitized = sanitizeLinkUrl(url)
        if (sanitized != null) {
            match.range to sanitized
        } else {
            null
        }
    }.toList()
}
