package com.nexachat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Render Free runtime profile against settings that would keep the
 * Neon compute awake.
 *
 * <p>Render Free tolerates one always-on service, but Neon Free has a monthly
 * compute budget and suspends after five idle minutes. A setting here that
 * polls the database more often than that spends the budget without any user
 * traffic. The values are asserted straight from the YAML, without booting a
 * Spring context, so the check stays fast and cannot be masked by test
 * properties.
 *
 * <p>The scheduled-method audit covers every {@code @Scheduled} method in the
 * main sources: each interval must resolve through the render profile to at
 * least {@link #MIN_RENDER_INTERVAL_MS}, or the method must be listed in
 * {@link #SCHEDULE_ALLOWLIST} with the reason it cannot wake Neon.
 */
class RenderProfileConfigurationTest {

    /** Neon suspends a free compute after five idle minutes. */
    private static final long NEON_SUSPEND_THRESHOLD_MS = 300_000L;

    /** Safety margin above the suspend threshold for every database-touching job. */
    private static final long MIN_RENDER_INTERVAL_MS = 360_000L;

    /**
     * Scheduled methods that legitimately resolve below
     * {@link #MIN_RENDER_INTERVAL_MS} on Render. Keys are {@code Class#method}.
     *
     * <p>Firebase beans do not exist on Render: {@code render.yaml} sets
     * {@code FIREBASE_REALTIME_ENABLED=false}, and every Firebase job bean is
     * behind {@code @Conditional(FirebaseRealtimeAvailableCondition)}, which
     * requires that flag and a database URL. The jobs therefore cannot wake
     * Neon even though their intervals are short when the bridge is enabled.
     *
     * <p>Reminders must fire on time, so their poll interval cannot be
     * stretched while the code still polls. The event-driven wake-up change
     * replaces the fixed poll; until it lands this entry stays.
     */
    private static final Map<String, String> SCHEDULE_ALLOWLIST = Map.of(
            "ReminderScheduler#poll",
            "Reminders must fire on time; the fixed 1 s poll is replaced by the event-driven wake-up change",
            "FirebaseCallStatusProjectionService#renewActiveCallProjections",
            "Firebase bridge is disabled on Render (FIREBASE_REALTIME_ENABLED=false), so the bean is absent",
            "FirebaseAuthorizationLeaseSweepJob#scheduledSweep",
            "Firebase bridge is disabled on Render (FIREBASE_REALTIME_ENABLED=false), so the bean is absent",
            "FirebaseProjectionRetryQueue#retryDue",
            "Firebase bridge is disabled on Render (FIREBASE_REALTIME_ENABLED=false), so the bean is absent");

    private static final String SCHEDULED_PREFIX = "@Scheduled";

    @Test
    void renderHikariPoolReleasesEveryIdleConnection() throws IOException {
        PropertySource<?> render = renderProfile();

        assertThat(render.getProperty("spring.datasource.hikari.minimum-idle"))
                .as("minimum-idle: an idle connection keeps Neon awake")
                .isEqualTo(0);
        assertThat(render.getProperty("spring.datasource.hikari.idle-timeout"))
                .as("idle-timeout")
                .isInstanceOf(Integer.class)
                .satisfies(value -> assertThat((Integer) value)
                        .isLessThanOrEqualTo((int) NEON_SUSPEND_THRESHOLD_MS));
        assertThat(render.getProperty("spring.datasource.hikari.maximum-pool-size"))
                .as("maximum-pool-size")
                .isInstanceOf(Integer.class)
                .satisfies(value -> assertThat((Integer) value).isLessThanOrEqualTo(10));
    }

    @Test
    void renderBoundsConcurrentUploadBuffersToTheSmallHeap() throws IOException {
        assertThat(renderProfile().getProperty("nexachat.uploads.max-concurrent"))
                .as("uploads.max-concurrent: each buffers up to 25 MB on a 512 MB box")
                .isInstanceOf(Integer.class)
                .satisfies(value -> assertThat((Integer) value).isBetween(1, 2));
    }

    @Test
    void renderBackgroundJobsRunLessOftenThanNeonsSuspendThreshold() throws IOException {
        PropertySource<?> render = renderProfile();

        assertThat(resolveIntervalMs(render, "${nexachat.rag.index.backfill-interval-ms:60000}"))
                .as("RAG index backfill interval")
                .isGreaterThanOrEqualTo(MIN_RENDER_INTERVAL_MS);
        assertThat(resolveIntervalMs(render, "${nexachat.auth.refresh-replay.cleanup-interval-ms:60000}"))
                .as("refresh-replay cleanup interval")
                .isGreaterThanOrEqualTo(MIN_RENDER_INTERVAL_MS);
    }

    @Test
    void everyScheduledMethodIsSlowOnRenderOrExplicitlyAllowlisted() throws IOException {
        List<ScheduledMethod> discovered = discoverScheduledMethods();
        assertThat(discovered)
                .as("the source scan must find the known scheduled methods")
                .hasSizeGreaterThanOrEqualTo(9);

        PropertySource<?> render = renderProfile();
        Map<String, String> seen = new LinkedHashMap<>();
        for (ScheduledMethod method : discovered) {
            assertThat(seen.put(method.id(), method.file()))
                    .as("duplicate scheduled method %s", method.id())
                    .isNull();

            if (method.hasUnresolvableSchedule()) {
                assertThat(SCHEDULE_ALLOWLIST)
                        .as("%s uses %s, which has no millisecond interval; add it to the allowlist "
                                + "with a reason", method.id(), method.schedule())
                        .containsKey(method.id());
                continue;
            }

            long intervalMs = resolveIntervalMs(render, method.schedule());
            if (intervalMs >= MIN_RENDER_INTERVAL_MS) {
                continue;
            }
            assertThat(SCHEDULE_ALLOWLIST)
                    .as("%s runs every %d ms on Render, below the %d ms bound; either raise it in "
                                    + "application-render.yml or allowlist it with a reason",
                            method.id(), intervalMs, MIN_RENDER_INTERVAL_MS)
                    .containsKey(method.id());
        }

        assertThat(SCHEDULE_ALLOWLIST.keySet())
                .as("stale allowlist entries (method missing or renamed)")
                .isSubsetOf(seen.keySet());
        SCHEDULE_ALLOWLIST.forEach((id, reason) ->
                assertThat(reason).as("allowlist reason for %s", id).isNotBlank());
    }

    @Test
    void firebaseRealtimeBridgeIsOffByDefaultAndOnRender() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        // The Firebase jobs are allowlisted because their beans are absent; that
        // is only true while the bridge opt-in defaults to false in every
        // profile that does not explicitly enable it.
        assertThat(application.getProperty("nexachat.firebase.realtime-enabled"))
                .isEqualTo("${FIREBASE_REALTIME_ENABLED:false}");
        assertThat(renderProfile().getProperty("nexachat.firebase.realtime-enabled"))
                .as("the render profile must not opt the Firebase bridge in")
                .isNull();
        assertThat(renderYaml())
                .as("render.yaml must keep FIREBASE_REALTIME_ENABLED=false")
                .containsPattern("key:\\s*FIREBASE_REALTIME_ENABLED\\s*\\n\\s*value:\\s*\"false\"");
    }

    private static String renderYaml() throws IOException {
        Path repositoryRoot = locateRepositoryRoot();
        return Files.readString(repositoryRoot.resolve("render.yaml"), StandardCharsets.UTF_8);
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Path candidate = current.resolve("render.yaml");
            if (Files.isRegularFile(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate render.yaml from "
                + System.getProperty("user.dir"));
    }

    private static PropertySource<?> renderProfile() throws IOException {
        return new YamlPropertySourceLoader()
                .load("render", new ClassPathResource("application-render.yml"))
                .get(0);
    }

    /**
     * Resolves one scheduling expression to milliseconds. Quoted placeholders
     * are resolved against the render profile first and fall back to their
     * inline default, exactly as Spring does.
     */
    private static long resolveIntervalMs(PropertySource<?> render, String expression) {
        String value = expression;
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.startsWith("${") && value.endsWith("}")) {
            String body = value.substring(2, value.length() - 1);
            int separator = body.indexOf(':');
            String key = separator < 0 ? body : body.substring(0, separator);
            Object configured = render.getProperty(key);
            if (configured != null) {
                value = configured.toString();
            } else if (separator >= 0) {
                value = body.substring(separator + 1);
            } else {
                throw new AssertionError("Scheduling placeholder " + expression
                        + " has no render value and no default");
            }
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            throw new AssertionError("Scheduling expression " + expression
                    + " does not resolve to a millisecond interval: " + value, exception);
        }
    }

    private static List<ScheduledMethod> discoverScheduledMethods() throws IOException {
        Path sources = mainSourcesRoot();
        List<ScheduledMethod> methods = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String className = file.getFileName().toString().replaceFirst("\\.java$", "");
                methods.addAll(parseScheduledMethods(source, className, file));
            }
        }
        return methods;
    }

    private static Path mainSourcesRoot() {
        Path moduleRelative = Path.of("src", "main", "java");
        if (Files.isDirectory(moduleRelative)) {
            return moduleRelative;
        }
        Path repositoryRelative = Path.of("server", "src", "main", "java");
        if (Files.isDirectory(repositoryRelative)) {
            return repositoryRelative;
        }
        throw new IllegalStateException("Could not locate the server main sources from "
                + System.getProperty("user.dir"));
    }

    private static List<ScheduledMethod> parseScheduledMethods(String source, String className, Path file) {
        List<ScheduledMethod> methods = new ArrayList<>();
        int cursor = 0;
        while ((cursor = source.indexOf(SCHEDULED_PREFIX, cursor)) >= 0) {
            int annotationStart = cursor;
            cursor = annotationStart + SCHEDULED_PREFIX.length();
            if (!startsWithAnnotation(source, annotationStart)) {
                continue;
            }
            int open = skipWhitespace(source, cursor);
            if (open >= source.length() || source.charAt(open) != '(') {
                continue;
            }
            int close = closingParenthesis(source, open);
            String body = source.substring(open + 1, close);
            String methodName = methodNameAfter(source, close + 1);
            String externalId = className + "#" + methodName;
            methods.add(interpretingSchedule(externalId, file.toString(), body));
            cursor = close + 1;
        }
        return methods;
    }

    /** {@code @Scheduled} in Javadoc or a comment does not start a line. */
    private static boolean startsWithAnnotation(String source, int annotationStart) {
        int lineStart = source.lastIndexOf('\n', annotationStart) + 1;
        return source.substring(lineStart, annotationStart).isBlank();
    }

    private static int skipWhitespace(String source, int index) {
        int current = index;
        while (current < source.length() && Character.isWhitespace(source.charAt(current))) {
            current++;
        }
        return current;
    }

    private static int closingParenthesis(String source, int open) {
        int depth = 0;
        boolean inString = false;
        boolean inCharacter = false;
        boolean escaped = false;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString || inCharacter) {
                if (character == '\\') {
                    escaped = true;
                } else if (inString && character == '"') {
                    inString = false;
                } else if (inCharacter && character == '\'') {
                    inCharacter = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '\'') {
                inCharacter = true;
            } else if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalStateException("Unbalanced @Scheduled annotation starting at offset " + open);
    }

    private static String methodNameAfter(String source, int index) {
        int position = skipAnnotationsAndWhitespace(source, index);
        int paren = source.indexOf('(', position);
        if (paren < 0) {
            throw new IllegalStateException("Could not find the method after @Scheduled: "
                    + source.substring(position, Math.min(position + 80, source.length())));
        }
        Matcher signature = Pattern.compile(
                        "([A-Za-z_$][A-Za-z0-9_$]*)\\s*$")
                .matcher(source.substring(position, paren));
        if (!signature.find()) {
            throw new IllegalStateException("Could not read the method name in: "
                    + source.substring(position, Math.min(position + 120, source.length())));
        }
        return signature.group(1);
    }

    private static int skipAnnotationsAndWhitespace(String source, int from) {
        int index = from;
        while (true) {
            index = skipWhitespace(source, index);
            if (index >= source.length() || source.charAt(index) != '@') {
                return index;
            }
            index++;
            while (index < source.length()
                    && (Character.isLetterOrDigit(source.charAt(index))
                    || source.charAt(index) == '.' || source.charAt(index) == '_')) {
                index++;
            }
            int open = skipWhitespace(source, index);
            index = (open < source.length() && source.charAt(open) == '(')
                    ? closingParenthesis(source, open) + 1
                    : open;
        }
    }

    /**
     * Reads the attribute that defines the primary interval. {@code fixedRate}
     * and {@code fixedDelay} constants are not resolved here: they have no
     * literal interval to check, so the audit requires an explicit allowlist
     * entry for them.
     */
    private static ScheduledMethod interpretingSchedule(String id, String file, String annotationBody) {
        Map<String, String> attributes = splitAttributes(annotationBody);
        for (String name : List.of("fixedDelayString", "fixedRateString", "fixedDelay", "fixedRate")) {
            String value = attributes.get(name);
            if (value == null) {
                continue;
            }
            if (value.startsWith("\"") && value.endsWith("\"")
                    && value.contains("${")) {
                return new ScheduledMethod(id, file, value, false);
            }
            if (value.matches("\\d+")) {
                return new ScheduledMethod(id, file, value, false);
            }
            return new ScheduledMethod(id, file, name + "=" + value, true);
        }
        if (attributes.containsKey("cron")) {
            return new ScheduledMethod(id, file, "cron=" + attributes.get("cron"), true);
        }
        throw new IllegalStateException(id + " has an @Scheduled annotation without a schedule: "
                + annotationBody.strip());
    }

    private static Map<String, String> splitAttributes(String body) {
        Map<String, String> attributes = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (inString) {
                current.append(character);
                if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
                current.append(character);
            } else if (character == '(' || character == '{') {
                depth++;
                current.append(character);
            } else if (character == ')' || character == '}') {
                depth--;
                current.append(character);
            } else if (character == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        for (String part : parts) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                continue;
            }
            attributes.put(part.substring(0, equals).strip(), part.substring(equals + 1).strip());
        }
        return attributes;
    }

    private record ScheduledMethod(String id, String file, String schedule, boolean unresolvable) {

        boolean hasUnresolvableSchedule() {
            return unresolvable;
        }
    }
}
