package com.nexachat.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the render profile's fallback for the admin console browser origin.
 *
 * <p>{@code render.yaml} sets {@code NEXACHAT_ADMIN_ALLOWED_ORIGINS} explicitly,
 * but the env var is the deployed value, not the only guard: the admin SPA is
 * served from {@code site/admin/} on the public site origin and calls the API
 * cross-origin, so a deploy that loses the variable must still admit that
 * origin. {@code application.yml} keeps an empty default on purpose — only the
 * render profile carries the same-site value as a fallback.
 *
 * <p>The check reads {@code application-render.yml} as text: no Spring context
 * boots and no environment is resolved, so a locally exported
 * {@code NEXACHAT_ADMIN_ALLOWED_ORIGINS} cannot mask a removed default. The
 * assertion fails if the default line disappears or drifts from the site
 * origin.
 */
class RenderAdminOriginDefaultTest {

    /** {@code nexachat.admin.allowed-origins} including its same-site fallback. */
    private static final String EXPECTED_ALLOWED_ORIGINS =
            "${NEXACHAT_ADMIN_ALLOWED_ORIGINS:https://nexachat.io.vn}";

    private static final Pattern TOP_LEVEL_KEY = Pattern.compile("^([A-Za-z0-9_-]+):");
    private static final Pattern ANY_KEY = Pattern.compile("^([A-Za-z0-9_-]+)\\s*:");
    private static final Pattern ALLOWED_ORIGINS_KEY =
            Pattern.compile("^allowed-origins\\s*:(?:\\s+(.*))?$");

    @Test
    void renderProfileDefaultsAdminBrowserOriginToTheSiteOrigin() throws IOException {
        assertThat(adminAllowedOriginsValues(renderProfileYaml()))
                .as("nexachat.admin.allowed-origins values in application-render.yml")
                .containsExactly(EXPECTED_ALLOWED_ORIGINS);
    }

    private static String renderProfileYaml() throws IOException {
        try (InputStream input = new ClassPathResource("application-render.yml").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Collects the scalar value of every {@code allowed-origins} key sitting
     * directly under {@code nexachat.admin}, so a same-named key in another
     * block cannot satisfy the pin. Blank lines and comments are skipped;
     * inline {@code #} comments and one layer of quotes are stripped from the
     * collected scalars.
     */
    private static List<String> adminAllowedOriginsValues(String yaml) {
        List<String> values = new ArrayList<>();
        String section = null;
        String subsection = null;
        for (String rawLine : yaml.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int indent = rawLine.length() - rawLine.stripLeading().length();
            if (indent == 0) {
                section = keyName(line, TOP_LEVEL_KEY);
                subsection = null;
                continue;
            }
            if (!"nexachat".equals(section)) {
                continue;
            }
            if (indent == 2) {
                subsection = keyName(line, ANY_KEY);
                continue;
            }
            if (indent == 4 && "admin".equals(subsection)) {
                Matcher origins = ALLOWED_ORIGINS_KEY.matcher(line);
                if (origins.matches()) {
                    values.add(scalarValue(origins.group(1)));
                }
            }
        }
        return values;
    }

    private static String keyName(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String scalarValue(String raw) {
        String value = raw == null ? "" : raw.strip();
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            value = value.substring(0, comment);
        }
        value = value.strip();
        if (value.length() > 1
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
