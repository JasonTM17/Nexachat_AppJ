package com.nexachat.firebase.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.nexachat.firebase.config.FirebaseDatabaseUrl;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirebaseRtdbRestClientProjectionValidationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final FirebaseRtdbRestClient client = new FirebaseRtdbRestClient(
            FirebaseDatabaseUrl.parse("https://nexachat.firebaseio.com"),
            GoogleCredentials.create(new AccessToken("test-token", null)),
            new ObjectMapper(), Duration.ofSeconds(1), Duration.ofSeconds(1));

    @Test
    void projectionPatchRejectsPathsOutsideAuthorizationMirrors() {
        assertThatThrownBy(() -> client.applyAuthorizationProjection(Map.of("messages/" + USER_ID, "private")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountProjectionRequiresExactServerOwnedFields() {
        assertThatThrownBy(() -> client.applyAuthorizationProjection(Map.of(
                "accounts/" + USER_ID, Map.of("active", true, "showPresence", true, "email", "private"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patchesCannotContainOverlappingParentAndChildPaths() {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put("sessions/" + USER_ID, null);
        updates.put("sessions/" + USER_ID + "/22222222-2222-4222-8222-222222222222", null);

        assertThatThrownBy(() -> client.applyAuthorizationProjection(updates))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expirySweepRejectsArbitraryDatabasePathsOrUnboundedBatches() {
        assertThatThrownBy(() -> client.deleteExpiredChildren("messages", "$value", 1L, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.deleteExpiredChildren("members/" + USER_ID, "$value", 1L, 501))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.expirePresenceEntries(USER_ID, 1L, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
