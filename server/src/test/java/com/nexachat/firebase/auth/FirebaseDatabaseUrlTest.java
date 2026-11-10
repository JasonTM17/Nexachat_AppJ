package com.nexachat.firebase.auth;

import com.nexachat.firebase.config.FirebaseDatabaseUrl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirebaseDatabaseUrlTest {

    @Test
    void acceptsOnlyRootHttpsRealtimeDatabaseHosts() {
        assertThat(FirebaseDatabaseUrl.parse("https://nexachat-default-rtdb.asia-southeast1.firebasedatabase.app/"))
                .hasToString("https://nexachat-default-rtdb.asia-southeast1.firebasedatabase.app");
        assertThat(FirebaseDatabaseUrl.parse("https://nexachat.firebaseio.com").value())
                .isEqualTo("https://nexachat.firebaseio.com");
    }

    @Test
    void rejectsUntrustedSchemesHostsAndUrlComponents() {
        for (String url : new String[]{
                "http://nexachat.firebaseio.com",
                "https://localhost",
                "https://attacker.example",
                "https://nexachat.firebaseio.com/other",
                "https://nexachat.firebaseio.com?redirect=https://attacker.example",
                "https://user:password@nexachat.firebaseio.com",
                "https://nexachat.firebaseio.com:443"
        }) {
            assertThatThrownBy(() -> FirebaseDatabaseUrl.parse(url))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
