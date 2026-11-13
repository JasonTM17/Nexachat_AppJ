package com.nexachat.recovery;

import com.nexachat.auth.JwtService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
class GoogleOnlyEmailRemovalIT {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withImagePullPolicy(image -> false);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;

    @Test
    void googleOnlyAccountKeepsItsLastAnchorAndReturnsConflict() throws Exception {
        User user = users.saveAndFlush(User.createGoogleOnly("Google user", email(), Instant.now()));
        mvc.perform(delete("/api/v1/users/me/email").accept("application/json")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_SIGN_IN_METHOD"));
        User persisted = users.findById(user.getId()).orElseThrow();
        assertThat(persisted.getEmail()).isEqualTo(user.getEmail());
        assertThat(persisted.hasVerifiedEmail()).isTrue();
    }

    @Test
    void passwordAndPhoneAccountsCanRemoveEmail() throws Exception {
        for (boolean password : new boolean[]{true, false}) {
            User user = User.create(password ? null : "+84900000101", password ? "test-hash" : null, "Anchored user");
            user.changeEmail(email());
            user.verifyEmail(Instant.now());
            user = users.saveAndFlush(user);
            mvc.perform(delete("/api/v1/users/me/email").accept("application/json")
                            .header("Authorization", "Bearer " + jwt.generateAccessToken(user)))
                    .andExpect(status().isNoContent());
            User persisted = users.findById(user.getId()).orElseThrow();
            assertThat(persisted.getEmail()).isNull();
            assertThat(persisted.hasVerifiedEmail()).isFalse();
        }
    }

    private static String email() {
        return UUID.randomUUID() + "@example.test";
    }
}
