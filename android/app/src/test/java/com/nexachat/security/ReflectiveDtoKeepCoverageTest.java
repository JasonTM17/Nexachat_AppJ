package com.nexachat.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.FirebaseBridgeResponse;
import com.nexachat.core.network.dto.FirebaseLeaseRequest;
import com.nexachat.core.network.dto.FirebaseLeaseResponse;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.PeerPresenceDto;
import com.nexachat.core.network.stomp.dto.PresenceEventDto;
import com.nexachat.core.network.stomp.dto.TypingSignalDto;
import com.squareup.moshi.Json;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Fails when a reflective Moshi model can lose the field names or no-arg
 * constructor R8 must preserve. Gson {@code @SerializedName} and Moshi
 * {@code @Json} are accepted only when every instance field is annotated and
 * the annotation keep rule is present; otherwise the class needs a full
 * {@code -keep class ... { *; }} rule.
 */
public final class ReflectiveDtoKeepCoverageTest {

    private static final Pattern KEEP_RULE = Pattern.compile(
            "-keep(?:classmembers)?\\s+(?:class|interface)\\s+(\\S+)\\s*\\{([^}]*)}",
            Pattern.DOTALL);
    private static final Pattern TYPE_TOKEN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
    private static final Set<String> NON_MODELS = Set.of(
            "Call", "Single", "Completable", "Response", "ResponseBody", "Header",
            "Body", "Path", "Query", "Part", "PartMap", "Field", "HTTP", "GET",
            "POST", "PUT", "PATCH", "DELETE", "Headers", "Streaming", "Url",
            "Nullable", "NonNull", "List", "Map", "Set", "Void", "String",
            "Integer", "Long", "Boolean", "Double", "Float", "Object", "Override");

    @Test
    public void reflectiveWireModelsKeepFieldNamesOrAnnotateEveryField() throws Exception {
        String rules = new String(Files.readAllBytes(proguardRules().toPath()), StandardCharsets.UTF_8);
        List<KeepPattern> fullKeeps = new ArrayList<>();
        boolean jsonFieldRule = false;
        Matcher matcher = KEEP_RULE.matcher(rules);
        while (matcher.find()) {
            String body = matcher.group(2);
            if (body.contains("@com.squareup.moshi.Json") && body.contains("<fields>")) {
                jsonFieldRule = true;
            }
            if (body.contains("*;")) {
                fullKeeps.add(KeepPattern.compile(matcher.group(1)));
            }
        }

        List<String> failures = new ArrayList<>();
        for (Class<?> model : discoverWireModels()) {
            if (coveredByFullKeep(fullKeeps, model.getName())) {
                continue;
            }
            List<String> bareFields = unannotatedInstanceFields(model);
            if (bareFields.isEmpty() && jsonFieldRule && hasJsonAdapterEscape(model)) {
                continue;
            }
            if (bareFields.isEmpty() && !hasInstanceField(model)) {
                continue;
            }
            failures.add(model.getName() + " is not covered by a full -keep rule"
                    + (bareFields.isEmpty() ? "" : " and has unannotated fields " + bareFields));
        }

        assertTrue(
                "Reflective Moshi models lost R8 name coverage:\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    @Test
    public void moshiAdaptersCreatedFromClassLiteralsAreCoveredByKeepRules() throws Exception {
        String rules = new String(Files.readAllBytes(proguardRules().toPath()), StandardCharsets.UTF_8);
        List<KeepPattern> keeps = new ArrayList<>();
        Matcher matcher = KEEP_RULE.matcher(rules);
        while (matcher.find()) {
            if (matcher.group(2).contains("*;")) {
                keeps.add(KeepPattern.compile(matcher.group(1)));
            }
        }

        List<String> failures = new ArrayList<>();
        for (File sourceFile : javaSources(sourceRoot())) {
            String source = new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8);
            for (String literal : moshiAdapterClassLiterals(source)) {
                Class<?> model = resolveClass(sourceFile, source, literal);
                if (model == null
                        || !model.getName().startsWith("com.nexachat.")
                        || !hasInstanceField(model)
                        || coveredByFullKeep(keeps, model.getName())) {
                    continue;
                }
                failures.add(model.getName() + " in " + sourceFile.getName());
            }
        }

        assertTrue(
                "Moshi adapters built from class literals have no R8 keep coverage:\n"
                        + String.join("\n", failures),
                failures.isEmpty());
    }

    /**
     * Firebase RTDB deserializes typed {@code getValue(X.class)},
     * {@code GenericTypeIndicator<X>} and POJO {@code setValue(x)} payloads with
     * the same reflective field rules as Moshi. The presence v2 and typing
     * paths currently exchange Maps only; a future typed model must be kept.
     */
    @Test
    public void firebaseRealtimeTypedModelsAreAbsentOrKept() throws Exception {
        List<KeepPattern> keeps = fullKeepPatterns();
        List<String> failures = new ArrayList<>();
        for (File sourceFile : javaSources(sourceRoot())) {
            String source = new String(Files.readAllBytes(sourceFile.toPath()), StandardCharsets.UTF_8);
            if (!source.contains("com.google.firebase.database")) {
                continue;
            }
            Set<String> literals = new LinkedHashSet<>();
            literals.addAll(firebaseTypedReadLiterals(source));
            literals.addAll(firebaseTypedWriteLiterals(source));
            for (String literal : literals) {
                Class<?> model = resolveClass(sourceFile, source, literal);
                if (model == null
                        || !model.getName().startsWith("com.nexachat.")
                        || !hasInstanceField(model)
                        || coveredByFullKeep(keeps, model.getName())) {
                    continue;
                }
                failures.add(model.getName() + " in " + sourceFile.getName());
            }
        }

        assertTrue(
                "Firebase RTDB typed models have no R8 keep coverage:\n"
                        + String.join("\n", failures),
                failures.isEmpty());
    }

    /**
     * Pins the unannotated field names Moshi serializes for the Firebase bridge,
     * presence v2 and typing beans. A rename here silently changes the wire
     * contract even though the R8 mapping check still passes.
     */
    @Test
    public void firebaseWireModelFieldsMatchTheWireContract() throws Exception {
        assertWireModel(FirebaseBridgeResponse.class,
                "customToken", "databaseUrl", "bridgeId", "leaseUntil", "transport");
        assertWireModel(FirebaseLeaseRequest.class, "bridgeId", "conversationIds");
        assertWireModel(FirebaseLeaseResponse.class, "leaseUntil", "transport");
        assertWireModel(PeerPresenceDto.class, "online", "lastSeenAt");
        assertWireModel(PresenceEventDto.class, "userId", "online", "lastSeenAt");
        assertWireModel(TypingSignalDto.class, "userId", "displayName");
    }

    private static void assertWireModel(Class<?> model, String... fields) throws Exception {
        Set<String> actual = new LinkedHashSet<>();
        for (Field field : model.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                actual.add(field.getName());
            }
        }
        assertEquals(model.getName() + " wire fields",
                new LinkedHashSet<>(List.of(fields)), actual);
        if (model != FirebaseLeaseRequest.class) {
            // Moshi's reflective response adapter needs a no-arg constructor.
            assertNotNull(model.getName() + " needs a no-arg constructor",
                    model.getDeclaredConstructor());
        }
    }

    private static List<KeepPattern> fullKeepPatterns() throws IOException {
        String rules = new String(Files.readAllBytes(proguardRules().toPath()), StandardCharsets.UTF_8);
        List<KeepPattern> keeps = new ArrayList<>();
        Matcher matcher = KEEP_RULE.matcher(rules);
        while (matcher.find()) {
            if (matcher.group(2).contains("*;")) {
                keeps.add(KeepPattern.compile(matcher.group(1)));
            }
        }
        return keeps;
    }

    private static Set<String> firebaseTypedReadLiterals(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher direct = Pattern.compile(
                "\\.getValue\\(\\s*([A-Za-z_$][\\w.$]*)\\.class\\s*\\)").matcher(source);
        while (direct.find()) {
            names.add(direct.group(1));
        }
        Matcher generic = Pattern.compile("GenericTypeIndicator\\s*<").matcher(source);
        while (generic.find()) {
            int open = generic.end();
            int close = closeGeneric(source, open);
            if (close < 0) {
                continue;
            }
            Matcher tokens = TYPE_TOKEN.matcher(source.substring(open, close));
            while (tokens.find()) {
                if (!NON_MODELS.contains(tokens.group(1))) {
                    names.add(tokens.group(1));
                }
            }
        }
        return names;
    }

    private static Set<String> firebaseTypedWriteLiterals(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher write = Pattern.compile(
                "\\.setValue\\(\\s*new\\s+([A-Za-z_$][\\w.$]*)\\s*\\(").matcher(source);
        while (write.find()) {
            names.add(write.group(1));
        }
        return names;
    }

    /**
     * Collects {@code moshi.adapter(X.class)} and
     * {@code moshi.adapter(Types.newParameterizedType(..., X.class))} arguments.
     * Adapters built from a variable {@code Type} are covered by the package
     * rules or the mapping check and cannot be resolved statically here.
     */
    private static Set<String> moshiAdapterClassLiterals(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher direct = Pattern.compile(
                "\\.adapter\\(\\s*([A-Za-z_$][\\w.$]*)\\.class\\s*\\)").matcher(source);
        while (direct.find()) {
            names.add(direct.group(1));
        }
        Matcher parameterized = Pattern.compile(
                "\\.adapter\\(\\s*Types\\.newParameterizedType\\(([^)]*)\\)\\s*\\)",
                Pattern.DOTALL).matcher(source);
        while (parameterized.find()) {
            Matcher literal = Pattern.compile(
                    "([A-Za-z_$][\\w.$]*)\\.class").matcher(parameterized.group(1));
            while (literal.find()) {
                names.add(literal.group(1));
            }
        }
        return names;
    }

    private static List<File> javaSources(File sourceRoot) throws IOException {
        try (var stream = Files.walk(sourceRoot.toPath())) {
            return stream
                    .map(path -> path.toFile())
                    .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                    .toList();
        }
    }

    private static boolean hasJsonAdapterEscape(Class<?> model) {
        for (Field field : model.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (!field.isAnnotationPresent(Json.class)
                    && field.getAnnotation(serializedNameAnnotation()) == null) {
                return false;
            }
        }
        return model.getDeclaredFields().length > 0;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends java.lang.annotation.Annotation> serializedNameAnnotation() {
        try {
            return (Class<? extends java.lang.annotation.Annotation>)
                    Class.forName("com.google.gson.annotations.SerializedName");
        } catch (ClassNotFoundException ignored) {
            return Json.class;
        }
    }

    private static List<String> unannotatedInstanceFields(Class<?> model) {
        List<String> bare = new ArrayList<>();
        for (Field field : model.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            if (!field.isAnnotationPresent(Json.class)
                    && field.getAnnotation(serializedNameAnnotation()) == null) {
                bare.add(field.getName());
            }
        }
        return bare;
    }

    private static boolean hasInstanceField(Class<?> model) {
        for (Field field : model.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                return true;
            }
        }
        return false;
    }

    private static boolean coveredByFullKeep(List<KeepPattern> patterns, String binaryName) {
        for (KeepPattern pattern : patterns) {
            if (pattern.matches(binaryName)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Class<?>> discoverWireModels() throws Exception {
        Set<Class<?>> models = new LinkedHashSet<>();
        models.addAll(classesUnder(MessageDto.class, "com/nexachat/core/network/dto"));
        models.addAll(classesUnder(MessageDto.class, "com/nexachat/core/network/stomp/dto"));

        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        for (File api : retrofitApis(sourceRoot())) {
            String source = new String(Files.readAllBytes(api.toPath()), StandardCharsets.UTF_8);
            if (!source.contains("retrofit2.http.")) {
                continue;
            }
            for (String simpleName : referencedModels(source)) {
                Class<?> resolved = resolveClass(api, source, simpleName);
                if (resolved != null) {
                    pending.add(resolved);
                }
            }
        }
        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!include(type) || !models.add(type)) {
                continue;
            }
            for (Class<?> nested : type.getDeclaredClasses()) {
                pending.add(nested);
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                pending.add(field.getType());
                if (field.getGenericType() instanceof ParameterizedType parameterized) {
                    for (Type argument : parameterized.getActualTypeArguments()) {
                        if (argument instanceof Class<?> argumentClass) {
                            pending.add(argumentClass);
                        }
                    }
                }
            }
        }
        models.removeIf(type -> !include(type) || !hasInstanceField(type));
        return models;
    }

    private static boolean include(Class<?> type) {
        return type != null
                && !type.isPrimitive()
                && !type.isEnum()
                && !type.isInterface()
                && !type.isArray()
                && type.getName().startsWith("com.nexachat.")
                && !type.getName().contains("$$")
                && !type.isSynthetic();
    }

    private static Set<Class<?>> classesUnder(Class<?> anchor, String relativePackage) throws Exception {
        URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
        File root = new File(location.toURI());
        File folder = new File(root, relativePackage);
        Set<Class<?>> classes = new LinkedHashSet<>();
        if (!folder.isDirectory()) {
            return classes;
        }
        String prefix = relativePackage.replace('/', '.');
        try (var stream = Files.walk(folder.toPath())) {
            for (File classFile : stream
                    .map(path -> path.toFile())
                    .filter(file -> file.getName().endsWith(".class"))
                    .toList()) {
                String name = classFile.getName().substring(0, classFile.getName().length() - 6);
                if (name.contains("$") && name.substring(name.lastIndexOf('$') + 1).matches("\\d+")) {
                    continue;
                }
                classes.add(Class.forName(prefix + "." + name));
            }
        }
        return classes;
    }

    private static List<File> retrofitApis(File sourceRoot) throws IOException {
        List<File> apis = new ArrayList<>();
        try (var stream = Files.walk(sourceRoot.toPath())) {
            for (File file : stream.map(path -> path.toFile()).filter(File::isFile).toList()) {
                if (file.getName().endsWith("Api.java")) {
                    apis.add(file);
                }
            }
        }
        return apis;
    }

    private static Set<String> referencedModels(String source) {
        Set<String> names = new LinkedHashSet<>();
        int search = 0;
        while (search < source.length()) {
            int call = source.indexOf("Call<", search);
            int body = source.indexOf("@Body", search);
            if (call < 0 && body < 0) {
                break;
            }
            if (body >= 0 && (call < 0 || body < call)) {
                Matcher type = Pattern.compile("@Body\\s+(?:@[\\w.]+\\s+)*([A-Z][A-Za-z0-9_]*)")
                        .matcher(source);
                if (type.find(body) && type.start() == body) {
                    names.add(type.group(1));
                }
                search = body + 5;
                continue;
            }
            int open = call + "Call<".length();
            int close = closeGeneric(source, open);
            if (close < 0) {
                break;
            }
            Matcher tokens = TYPE_TOKEN.matcher(source.substring(open, close));
            while (tokens.find()) {
                if (!NON_MODELS.contains(tokens.group(1))) {
                    names.add(tokens.group(1));
                }
            }
            search = close + 1;
        }
        return names;
    }

    private static int closeGeneric(String source, int open) {
        int depth = 1;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static Class<?> resolveClass(File apiFile, String source, String simpleName) {
        if (simpleName.contains(".")) {
            return forName(simpleName);
        }
        Matcher imported = Pattern.compile(
                "(?m)^import\\s+([\\w.]+\\." + Pattern.quote(simpleName) + ")\\s*;").matcher(source);
        if (imported.find()) {
            return forName(imported.group(1));
        }
        Matcher pkg = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(source);
        if (!pkg.find()) {
            return null;
        }
        String packageName = pkg.group(1);
        Class<?> samePackage = forName(packageName + "." + simpleName);
        if (samePackage != null) {
            return samePackage;
        }
        Class<?> nested = forName(packageName + "." + apiFile.getName().replace(".java", "")
                + "$" + simpleName);
        if (nested != null) {
            return nested;
        }
        return null;
    }

    private static Class<?> forName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | ExceptionInInitializerError | NoClassDefFoundError ignored) {
            return null;
        }
    }

    private static File proguardRules() {
        File module = moduleDir();
        File rules = new File(module, "proguard-rules.pro");
        assertTrue("proguard-rules.pro must exist", rules.isFile());
        return rules;
    }

    private static File sourceRoot() {
        File root = new File(moduleDir(), "src/main/java");
        assertTrue("app java sources must exist", root.isDirectory());
        return root;
    }

    private static File moduleDir() {
        File current = new File("").getAbsoluteFile();
        while (current != null) {
            if (new File(current, "proguard-rules.pro").isFile()
                    && new File(current, "src/main/java").isDirectory()) {
                return current;
            }
            current = current.getParentFile();
        }
        throw new IllegalStateException("Could not locate the Android app module");
    }

    private static final class KeepPattern {
        private final Pattern regex;

        private KeepPattern(Pattern regex) {
            this.regex = regex;
        }

        static KeepPattern compile(String proguardPattern) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < proguardPattern.length();) {
                if (proguardPattern.startsWith("**", i)) {
                    regex.append(".*");
                    i += 2;
                } else if (proguardPattern.charAt(i) == '*') {
                    regex.append("[^.]*");
                    i++;
                } else {
                    regex.append(Pattern.quote(String.valueOf(proguardPattern.charAt(i))));
                    i++;
                }
            }
            return new KeepPattern(Pattern.compile(regex.toString()));
        }

        boolean matches(String binaryName) {
            return regex.matcher(binaryName).matches();
        }
    }
}
