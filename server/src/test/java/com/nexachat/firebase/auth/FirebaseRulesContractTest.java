package com.nexachat.firebase.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseRulesContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void candidateRulesAreValidAndKeepServerOnlyMirrorsPrivate() throws Exception {
        JsonNode root = objectMapper.readTree(Files.readString(rulesPath()));
        JsonNode rules = root.path("rules");

        assertThat(rules.path(".read").asBoolean()).isFalse();
        assertThat(rules.path(".write").asBoolean()).isFalse();
        for (String path : new String[]{"control", "accounts", "sessions", "members", "presenceReaders", "blocked"}) {
            assertThat(rules.path(path).path(".read").asBoolean()).as(path + " read").isFalse();
            assertThat(rules.path(path).path(".write").asBoolean()).as(path + " write").isFalse();
        }
    }

    @Test
    void candidateRulesAuthorizeOnlyLeasedPresenceTypingAndParticipantCallReads() throws Exception {
        JsonNode rules = objectMapper.readTree(Files.readString(rulesPath())).path("rules");

        String presenceRead = rules.path("presence").path("$uid").path(".read").asText();
        assertThat(presenceRead).contains("auth.token.bridgeId", "control/enabled", "presencePrivacyVersion",
                "sessions", "showPresence", "presenceReaders", "members", "blocked");
        assertThat(rules.path("presence").path("$uid").path("$bridge").path(".write").asText())
                .contains("auth.uid == $uid", "auth.token.bridgeId == $bridge", "sessions");

        JsonNode typing = rules.path("typing").path("$cid").path("$uid");
        assertThat(typing.path(".read").asText()).contains("members", "blocked", "expiresAt");
        assertThat(typing.path(".write").asText()).contains("auth.uid == $uid", "members", "updatedAt");

        JsonNode call = rules.path("calls").path("$callId");
        assertThat(call.path(".read").asText()).contains("callerId", "calleeId", "members", "blocked", "expiresAt");
        assertThat(call.path(".write").asBoolean()).isFalse();
    }

    private static Path rulesPath() {
        Path fromModule = Path.of("..", "tools", "firebase-rules", "database.rules.json");
        return Files.exists(fromModule)
                ? fromModule
                : Path.of("tools", "firebase-rules", "database.rules.json");
    }
}
