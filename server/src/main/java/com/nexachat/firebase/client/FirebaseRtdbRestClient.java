package com.nexachat.firebase.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.nexachat.firebase.config.FirebaseDatabaseUrl;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Google OAuth authenticated RTDB REST client with redirects disabled. */
public final class FirebaseRtdbRestClient implements FirebaseRtdbClient {

    public static final String DATABASE_SCOPE = "https://www.googleapis.com/auth/firebase.database";
    public static final String USERINFO_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email";
    private static final Pattern UUID_KEY = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final FirebaseDatabaseUrl databaseUrl;
    private final GoogleCredentials credentials;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;

    public FirebaseRtdbRestClient(FirebaseDatabaseUrl databaseUrl,
                                  GoogleCredentials credentials,
                                  ObjectMapper objectMapper,
                                  Duration connectTimeout,
                                  Duration requestTimeout) {
        this.databaseUrl = databaseUrl;
        this.credentials = credentials.createScoped(Set.of(DATABASE_SCOPE, USERINFO_EMAIL_SCOPE));
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void applyAuthorizationProjection(Map<String, Object> updates) {
        validateProjection(updates);
        patch(updates);
    }

    @Override
    public void applyCallStatusProjection(Map<String, Object> updates) {
        validateCallStatusProjection(updates);
        patch(updates);
    }

    @Override
    public int deleteExpiredChildren(String parentPath, String orderBy, long cutoffMillis, int limit) {
        validateSweep(parentPath, orderBy, limit);
        URI queryUri = queryUri(parentPath, orderBy, cutoffMillis, limit);
        JsonNode candidates;
        try {
            candidates = objectMapper.readTree(send("GET", queryUri, null));
        } catch (FirebaseProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database returned invalid expiry data");
        }
        if (candidates == null || !candidates.isObject()) {
            return 0;
        }

        int deleted = 0;
        var fields = candidates.fieldNames();
        while (fields.hasNext()) {
            String childKey = fields.next();
            if (!UUID_KEY.matcher(childKey).matches()) {
                continue;
            }
            if (deleteIfStillExpired(parentPath + "/" + childKey, orderBy, cutoffMillis)) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public int expirePresenceEntries(UUID userId, long cutoffMillis, int limit) {
        if (userId == null || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Firebase presence expiry request is invalid");
        }
        String parentPath = "presence/" + userId;
        JsonNode expired = readQuery(parentPath, "leaseUntil", cutoffMillis, limit);
        int changed = 0;
        if (expired != null && expired.isObject()) {
            var fields = expired.fieldNames();
            while (fields.hasNext()) {
                String bridgeId = fields.next();
                if (UUID_KEY.matcher(bridgeId).matches()
                        && markOfflineIfStillExpired(parentPath + "/" + bridgeId, cutoffMillis)) {
                    changed++;
                }
            }
        }

        // Presence is per bridge. If its bounded current snapshot fits, retain only the most recent offline marker.
        JsonNode current = readOrderedChildren(parentPath, limit + 1);
        if (current == null || !current.isObject() || current.size() > limit) {
            return changed;
        }
        String latestOfflineBridge = null;
        long latestLastSeen = Long.MIN_VALUE;
        var currentFields = current.fields();
        while (currentFields.hasNext()) {
            var entry = currentFields.next();
            JsonNode node = entry.getValue();
            JsonNode online = node == null ? null : node.get("online");
            JsonNode lastSeen = node == null ? null : node.get("lastSeen");
            if (online != null && online.isBoolean() && !online.booleanValue()
                    && lastSeen != null && lastSeen.isIntegralNumber() && lastSeen.canConvertToLong()
                    && lastSeen.longValue() > latestLastSeen) {
                latestLastSeen = lastSeen.longValue();
                latestOfflineBridge = entry.getKey();
            }
        }
        if (latestOfflineBridge == null) {
            return changed;
        }
        var cleanupFields = current.fieldNames();
        while (cleanupFields.hasNext()) {
            String bridgeId = cleanupFields.next();
            if (!bridgeId.equals(latestOfflineBridge) && UUID_KEY.matcher(bridgeId).matches()) {
                JsonNode node = current.get(bridgeId);
                JsonNode online = node == null ? null : node.get("online");
                if (online != null && online.isBoolean() && !online.booleanValue()
                        && deleteOlderOfflineChild(parentPath + "/" + bridgeId, latestLastSeen)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private boolean deleteIfStillExpired(String relativePath, String orderBy, long cutoffMillis) {
        URI uri = databaseUrl.resolve(relativePath);
        HttpResponse<String> current = sendRaw("GET", uri, null, Map.of("X-Firebase-ETag", "true"));
        requireSuccess(current);
        String etag = current.headers().firstValue("ETag").orElseThrow(() ->
                new FirebaseProviderException("Firebase Realtime Database did not return an ETag"));
        JsonNode currentValue;
        try {
            currentValue = objectMapper.readTree(current.body());
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database returned invalid expiry data");
        }
        JsonNode expiry = "$value".equals(orderBy) ? currentValue
                : currentValue == null ? null : currentValue.get(orderBy);
        if (expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToLong()
                || expiry.longValue() > cutoffMillis) {
            return false;
        }
        return deleteIfMatch(uri, etag);
    }

    private boolean markOfflineIfStillExpired(String relativePath, long cutoffMillis) {
        URI uri = databaseUrl.resolve(relativePath);
        EtagValue current = readWithEtag(uri);
        JsonNode node = current.value();
        JsonNode online = node == null ? null : node.get("online");
        JsonNode leaseUntil = node == null ? null : node.get("leaseUntil");
        if (online == null || !online.isBoolean() || !online.booleanValue()
                || leaseUntil == null || !leaseUntil.isIntegralNumber() || !leaseUntil.canConvertToLong()
                || leaseUntil.longValue() > cutoffMillis) {
            return false;
        }
        Map<String, Object> offline = new LinkedHashMap<>();
        offline.put("online", false);
        offline.put("lastSeen", Map.of(".sv", "timestamp"));
        offline.put("updatedAt", Map.of(".sv", "timestamp"));
        offline.put("leaseUntil", leaseUntil.longValue());
        HttpResponse<String> response = sendRaw("PUT", uri, offline, Map.of("If-Match", current.etag()));
        if (response.statusCode() == 412) {
            return false;
        }
        requireSuccess(response);
        return true;
    }

    private boolean deleteOlderOfflineChild(String relativePath, long latestLastSeen) {
        URI uri = databaseUrl.resolve(relativePath);
        EtagValue current = readWithEtag(uri);
        JsonNode online = current.value() == null ? null : current.value().get("online");
        JsonNode lastSeen = current.value() == null ? null : current.value().get("lastSeen");
        if (online == null || !online.isBoolean() || online.booleanValue()
                || lastSeen == null || !lastSeen.isIntegralNumber() || !lastSeen.canConvertToLong()
                || lastSeen.longValue() > latestLastSeen) {
            return false;
        }
        return deleteIfMatch(uri, current.etag());
    }

    private boolean deleteIfMatch(URI uri, String etag) {
        HttpResponse<String> deleted = sendRaw("DELETE", uri, null, Map.of("If-Match", etag));
        if (deleted.statusCode() == 412) {
            // A concurrent update changed this child after the ETag read; the next sweep will re-evaluate it.
            return false;
        }
        requireSuccess(deleted);
        return true;
    }

    private EtagValue readWithEtag(URI uri) {
        HttpResponse<String> response = sendRaw("GET", uri, null, Map.of("X-Firebase-ETag", "true"));
        requireSuccess(response);
        String etag = response.headers().firstValue("ETag").orElseThrow(() ->
                new FirebaseProviderException("Firebase Realtime Database did not return an ETag"));
        try {
            return new EtagValue(objectMapper.readTree(response.body()), etag);
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database returned invalid expiry data");
        }
    }

    private JsonNode readQuery(String parentPath, String orderBy, long cutoffMillis, int limit) {
        try {
            return objectMapper.readTree(send("GET", queryUri(parentPath, orderBy, cutoffMillis, limit), null));
        } catch (FirebaseProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database returned invalid expiry data");
        }
    }

    private JsonNode readOrderedChildren(String parentPath, int limit) {
        String encodedOrder = URLEncoder.encode("\"$key\"", StandardCharsets.UTF_8);
        URI uri = URI.create(databaseUrl.resolve(parentPath) + "?orderBy=" + encodedOrder + "&limitToFirst=" + limit);
        try {
            return objectMapper.readTree(send("GET", uri, null));
        } catch (FirebaseProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database returned invalid presence data");
        }
    }

    private record EtagValue(JsonNode value, String etag) {
    }

    private URI queryUri(String parentPath, String orderBy, long cutoffMillis, int limit) {
        String encodedOrder = URLEncoder.encode("\"" + orderBy + "\"", StandardCharsets.UTF_8);
        return URI.create(databaseUrl.resolve(parentPath) + "?orderBy=" + encodedOrder
                + "&endAt=" + cutoffMillis + "&limitToFirst=" + limit);
    }

    @Override
    public Long readSessionLease(UUID userId, UUID bridgeId) {
        URI uri = databaseUrl.resolve("sessions/" + userId + "/" + bridgeId + "/until");
        String response = send("GET", uri, null);
        JsonNode value;
        try {
            value = objectMapper.readTree(response);
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database returned an invalid lease value");
        }
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new FirebaseProviderException("Firebase Realtime Database returned an invalid lease value");
        }
        return value.longValue();
    }

    @Override
    public void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                                  Map<UUID, Long> conversationLeaseUntilMillis) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sessions/" + userId + "/" + bridgeId + "/until", leaseUntilMillis);
        conversationLeaseUntilMillis.forEach((conversationId, until) ->
                changes.put("members/" + conversationId + "/" + userId, until));
        patch(changes);
    }

    @Override
    public void deleteBridge(UUID userId, UUID bridgeId) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sessions/" + userId + "/" + bridgeId, null);
        changes.put("presence/" + userId + "/" + bridgeId, null);
        patch(changes);
    }

    @Override
    public void revokeUser(UUID userId, List<UUID> conversationIds) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("sessions/" + userId, null);
        changes.put("presence/" + userId, null);
        for (UUID conversationId : new ArrayList<>(conversationIds)) {
            changes.put("typing/" + conversationId + "/" + userId, null);
        }
        patch(changes);
    }

    private void patch(Map<String, Object> changes) {
        send("PATCH", databaseUrl.rootJson(), changes);
    }

    private String send(String method, URI uri, Object body) {
        HttpResponse<String> response = sendRaw(method, uri, body, Map.of());
        requireSuccess(response);
        return response.body();
    }

    private HttpResponse<String> sendRaw(String method, URI uri, Object body, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + accessToken());
        headers.forEach(builder::header);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            try {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                objectMapper.writeValueAsString(body)));
            } catch (IOException ex) {
                throw new FirebaseProviderException("Firebase Realtime Database request could not be encoded");
            }
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new FirebaseProviderException("Firebase Realtime Database request was interrupted");
        } catch (Exception ex) {
            throw new FirebaseProviderException("Firebase Realtime Database request failed");
        }
        return response;
    }

    private static void requireSuccess(HttpResponse<?> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new FirebaseProviderException(
                    "Firebase Realtime Database returned HTTP " + response.statusCode());
        }
    }

    private static void validateProjection(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("Firebase authorization projection patch must not be empty");
        }
        List<String> paths = new ArrayList<>(updates.keySet());
        for (String path : paths) {
            String[] parts = path == null ? new String[0] : path.split("/", -1);
            if (parts.length == 2 && "control".equals(parts[0]) && "enabled".equals(parts[1])) {
                requireType(updates.get(path), Boolean.class, path);
            } else if (parts.length == 2 && "accounts".equals(parts[0]) && UUID_KEY.matcher(parts[1]).matches()) {
                Object value = updates.get(path);
                if (value != null && (!(value instanceof Map<?, ?> account)
                        || !account.keySet().equals(Set.of("active", "showPresence"))
                        || !(account.get("active") instanceof Boolean)
                        || !(account.get("showPresence") instanceof Boolean))) {
                    throw new IllegalArgumentException("Firebase account projection has an invalid shape");
                }
            } else if (parts.length == 3 && "presenceReaders".equals(parts[0])
                    && UUID_KEY.matcher(parts[1]).matches() && UUID_KEY.matcher(parts[2]).matches()) {
                Object value = updates.get(path);
                if (value != null && (!(value instanceof String conversationId)
                        || !UUID_KEY.matcher(conversationId).matches())) {
                    throw new IllegalArgumentException("Firebase presence-reader projection has an invalid value");
                }
            } else if (parts.length == 3 && ("members".equals(parts[0]) || "blocked".equals(parts[0]))
                    && UUID_KEY.matcher(parts[1]).matches() && UUID_KEY.matcher(parts[2]).matches()) {
                Object value = updates.get(path);
                if (value != null && !("members".equals(parts[0])
                        && (value instanceof Long || value instanceof Integer)
                        && ((Number) value).longValue() > 0)
                        && !("blocked".equals(parts[0]) && value instanceof Boolean)) {
                    throw new IllegalArgumentException("Firebase authorization projection has an invalid value");
                }
            } else if ((parts.length == 2 && Set.of("sessions", "presence", "presenceReaders", "blocked").contains(parts[0])
                    && UUID_KEY.matcher(parts[1]).matches())
                    || (parts.length == 3 && Set.of("sessions", "presence", "typing").contains(parts[0])
                    && UUID_KEY.matcher(parts[1]).matches() && UUID_KEY.matcher(parts[2]).matches())
                    || (parts.length == 2 && "calls".equals(parts[0]) && UUID_KEY.matcher(parts[1]).matches())) {
                if (updates.get(path) != null) {
                    throw new IllegalArgumentException("Firebase authorization projection only permits cleanup here");
                }
            } else {
                throw new IllegalArgumentException("Firebase authorization projection path is not allowed");
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                if (paths.get(i).startsWith(paths.get(j) + "/")
                        || paths.get(j).startsWith(paths.get(i) + "/")) {
                    throw new IllegalArgumentException("Firebase authorization projection paths overlap");
                }
            }
        }
    }

    static void validateCallStatusProjection(Map<String, Object> updates) {
        if (updates == null || updates.isEmpty() || updates.size() > 500) {
            throw new IllegalArgumentException("Firebase call-status projection patch must not be empty");
        }
        List<String> paths = new ArrayList<>(updates.keySet());
        Set<String> fields = Set.of("cid", "callerId", "calleeId", "state", "updatedAt", "expiresAt");
        for (String path : paths) {
            String[] parts = path == null ? new String[0] : path.split("/", -1);
            if (parts.length != 2 || !"calls".equals(parts[0]) || !UUID_KEY.matcher(parts[1]).matches()) {
                throw new IllegalArgumentException("Firebase call-status projection path is not allowed");
            }
            Object value = updates.get(path);
            if (value == null) {
                continue;
            }
            if (!(value instanceof Map<?, ?> call) || !call.keySet().equals(fields)) {
                throw new IllegalArgumentException("Firebase call-status projection has an invalid shape");
            }
            if (!isUuid(call.get("cid")) || !isUuid(call.get("callerId")) || !isUuid(call.get("calleeId"))
                    || call.get("callerId").equals(call.get("calleeId"))) {
                throw new IllegalArgumentException("Firebase call-status projection has invalid participants");
            }
            Object stateValue = call.get("state");
            if (!(stateValue instanceof String state)
                    || !Set.of("RINGING", "ACTIVE", "ENDED").contains(state)) {
                throw new IllegalArgumentException("Firebase call-status projection has an invalid state");
            }
            long updatedAt = positiveEpochMillis(call.get("updatedAt"), "updatedAt");
            long expiresAt = positiveEpochMillis(call.get("expiresAt"), "expiresAt");
            long maximumTtl = switch (state) {
                case "RINGING" -> 45_000L;
                case "ACTIVE" -> 120_000L;
                case "ENDED" -> 60_000L;
                default -> throw new IllegalArgumentException("Firebase call-status projection has an invalid state");
            };
            if (expiresAt <= updatedAt || expiresAt - updatedAt > maximumTtl) {
                throw new IllegalArgumentException("Firebase call-status projection expiry exceeds its lease");
            }
        }
        for (int i = 0; i < paths.size(); i++) {
            for (int j = i + 1; j < paths.size(); j++) {
                if (paths.get(i).startsWith(paths.get(j) + "/")
                        || paths.get(j).startsWith(paths.get(i) + "/")) {
                    throw new IllegalArgumentException("Firebase call-status projection paths overlap");
                }
            }
        }
    }

    private static boolean isUuid(Object value) {
        return value instanceof String id && UUID_KEY.matcher(id).matches();
    }

    private static long positiveEpochMillis(Object value, String field) {
        if (!(value instanceof Long || value instanceof Integer)) {
            throw new IllegalArgumentException("Firebase call-status projection has an invalid " + field);
        }
        long millis = ((Number) value).longValue();
        if (millis < 0) {
            throw new IllegalArgumentException("Firebase call-status projection has an invalid " + field);
        }
        return millis;
    }

    private static void validateSweep(String parentPath, String orderBy, int limit) {
        String[] parts = parentPath == null ? new String[0] : parentPath.split("/", -1);
        boolean allowedPath = parts.length == 2
                && (("members".equals(parts[0]) && UUID_KEY.matcher(parts[1]).matches())
                || ("typing".equals(parts[0]) && UUID_KEY.matcher(parts[1]).matches())
                || ("sessions".equals(parts[0]) && UUID_KEY.matcher(parts[1]).matches()));
        boolean allowedField = ("members".equals(parts.length > 0 ? parts[0] : "") && "$value".equals(orderBy))
                || ("typing".equals(parts.length > 0 ? parts[0] : "") && "expiresAt".equals(orderBy))
                || ("sessions".equals(parts.length > 0 ? parts[0] : "") && "until".equals(orderBy));
        if (!allowedPath || !allowedField || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Firebase expiry sweep request is invalid");
        }
    }

    private static void requireType(Object value, Class<?> type, String path) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Firebase authorization projection has an invalid value at " + path);
        }
    }

    private String accessToken() {
        try {
            credentials.refreshIfExpired();
            AccessToken accessToken = credentials.getAccessToken();
            if (accessToken == null || accessToken.getTokenValue() == null
                    || accessToken.getTokenValue().isBlank()) {
                accessToken = credentials.refreshAccessToken();
            }
            return accessToken.getTokenValue();
        } catch (IOException ex) {
            throw new FirebaseProviderException("Firebase Realtime Database credentials could not be refreshed");
        }
    }
}
