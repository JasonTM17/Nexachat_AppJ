package com.nexachat.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credential-shaped and message-shaped content in log events.
 *
 * <p>The patterns are deliberately conservative: over-redaction is safe,
 * under-redaction is not. The matcher is applied to the rendered log message
 * and to exception text before the JSON encoder writes it.
 */
public final class Redactor {

    private static final String REDACTED = "[REDACTED]";

    /** {@code Authorization: Bearer <token>} (JWTs, FCM access tokens, API keys). */
    private static final Pattern BEARER =
            Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*");

    /** Compact JWS JWTs, independent of the header. */
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*\\b");

    /** Named credential fields: api_key=..., apiKey: ..., secret=..., token=..., bearer=... */
    private static final Pattern NAMED_CREDENTIAL = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|passwd|token|bearer)\\s*[:=]\\s*['\"]?"
                    + "([A-Za-z0-9_\\-._~+/=]{8,})['\"]?");

    /** FCM registration tokens, which are long and carry no distinguishing shape. */
    private static final Pattern FCM_TOKEN = Pattern.compile(
            "(?i)(fcm[_-]?token|registration[_-]?token|device[_-]?token)\\s*[:=]\\s*['\"]?([A-Za-z0-9_:\\-]{20,})['\"]?");

    /** Google API keys and OAuth access tokens. */
    private static final Pattern GOOGLE_KEY =
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b|\\bya29\\.[0-9A-Za-z_-]{20,}\\b");

    /** OpenAI-compatible secret keys. */
    private static final Pattern OPENAI_KEY =
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b");

    /** Phone numbers: local (leading 0) or international (+country code). */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+\\d{1,3}[-. ]?)?(?:0\\d{2,3}[-. ]?\\d{3}[-. ]?\\d{3,4}"
                    + "|\\d{3}[-. ]?\\d{3,4}[-. ]?\\d{3,4})(?!\\d)");

    /** Explicit phone fields, which are the common leak in request logs. */
    private static final Pattern PHONE_FIELD = Pattern.compile(
            "(?i)(phone|mobile|telephone|tel)\\s*[:=]\\s*['\"]?(\\+?[0-9\\s().-]{7,20})['\"]?");

    /** JSON message/content fields. */
    private static final Pattern CONTENT_JSON = Pattern.compile(
            "(?i)(\"content\"|\"body\"|\"text\"|\"message\")\\s*:\\s*\"[^\"]*\"");

    /** Query/form content fields. */
    private static final Pattern CONTENT_PARAM = Pattern.compile(
            "(?i)\\b(content|body|text)\\s*=\\s*[^&\\s,;\"']+");

    private Redactor() {
    }

    /** Returns a copy of {@code input} with every known secret shape masked. */
    public static String redact(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        result = replaceAll(BEARER, result, "Bearer " + REDACTED);
        result = replaceAll(JWT, result, REDACTED);
        result = replaceAll(NAMED_CREDENTIAL, result, "$1=" + REDACTED);
        result = replaceAll(FCM_TOKEN, result, "$1=" + REDACTED);
        result = replaceAll(GOOGLE_KEY, result, REDACTED);
        result = replaceAll(OPENAI_KEY, result, REDACTED);
        result = replaceAll(PHONE_FIELD, result, "$1=" + REDACTED);
        result = replaceAll(PHONE, result, REDACTED);
        result = replaceAll(CONTENT_JSON, result, "$1:\"" + REDACTED + "\"");
        result = replaceAll(CONTENT_PARAM, result, "$1=" + REDACTED);
        return result;
    }

    private static String replaceAll(Pattern pattern, String input, String replacement) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        return matcher.replaceAll(replacement);
    }
}
