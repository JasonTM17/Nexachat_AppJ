package com.nexachat.summary.llm;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public final class Redactor {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+\\b");
    private static final Pattern URL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])(?:(?:https?|ftp)://|www\\.)[^\\s<>\\\"']+"
                    + "|(?<![@\\p{L}\\p{N}_])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}"
                    + "(?::\\d+)?(?:/[^\\s<>\\\"']*)?");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\+?\\p{Nd}[\\p{Nd}(). -]{4,}\\p{Nd}(?![\\p{L}\\p{N}])");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\p{Nd}{7,}");
    private static final Pattern TOKEN = Pattern.compile(
            "(?=[A-Za-z0-9_-]{24,})(?=[A-Za-z0-9_-]*[A-Za-z_-])[A-Za-z0-9_-]{24,}");

    public String redact(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // URLs first: their credentials contain '@', which the email pattern
        // would otherwise clip and leave a malformed URL behind.
        String redacted = redactUrls(input);
        redacted = EMAIL.matcher(redacted).replaceAll("[REDACTED_EMAIL]");
        redacted = TOKEN.matcher(redacted).replaceAll("[REDACTED_TOKEN]");
        redacted = redactPhones(redacted);
        redacted = LONG_DIGITS.matcher(redacted).replaceAll("[REDACTED_NUMBER]");
        return redacted;
    }

    private static String redactUrls(String input) {
        Matcher matcher = URL.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group();
            int end = raw.length();
            while (end > 0 && ".,;:!?)]}".indexOf(raw.charAt(end - 1)) >= 0) {
                end--;
            }
            String url = raw.substring(0, end);
            String punctuation = raw.substring(end);
            String domain = domainOf(url);
            matcher.appendReplacement(output, Matcher.quoteReplacement(domain + punctuation));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String domainOf(String value) {
        String normalized = value.regionMatches(true, 0, "www.", 0, 4) || !value.contains("://")
                ? "https://" + value
                : value;
        try {
            URI uri = new URI(normalized);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase(Locale.ROOT);
            }
            String authority = uri.getRawAuthority();
            if (authority != null) {
                int at = authority.lastIndexOf('@');
                return authority.substring(at + 1).toLowerCase(Locale.ROOT);
            }
        } catch (URISyntaxException ignored) {
            // The input is still removed; a malformed URL must not retain its path or query.
        }
        return "[REDACTED_URL]";
    }

    private static String redactPhones(String input) {
        Matcher matcher = PHONE.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String value = matcher.group();
            long digits = value.codePoints().filter(Character::isDigit).count();
            matcher.appendReplacement(output, Matcher.quoteReplacement(
                    digits >= 7 && digits <= 15 ? "[REDACTED_PHONE]" : value));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
