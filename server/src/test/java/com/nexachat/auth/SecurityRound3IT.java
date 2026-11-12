package com.nexachat.auth;

import com.nexachat.testutil.ItPhoneRanges;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Round-3 adversarial cases: bcrypt's 72-byte input, audience-unbound access
 * tokens, off-origin group avatars, and HTML/SVG disguised as text.
 */
class SecurityRound3IT extends SecurityITBase {

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    void registerRejectsPasswordWhoseUtf8FormExceedsBcryptLimit() throws Exception {
        // 40 code points, 80 UTF-8 bytes. Bean validation allows it (max 72 chars)
        // but bcrypt only hashes the first 72 bytes.
        String password = "é".repeat(40);
        postJson("/api/v1/auth/register", Map.of(
                "phone", ItPhoneRanges.phoneFor(SecurityRound3IT.class, "+84924400011"),
                "password", password,
                "displayName", "Sec User"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void accessTokenWithoutAudienceIsRejected() throws Exception {
        Registered user = register(ItPhoneRanges.phoneFor(SecurityRound3IT.class, "+84924400012"));
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        String unbound = Jwts.builder()
                .subject(user.userId().toString())
                .claim(JwtService.CLAIM_TYPE, JwtService.TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact();

        getWithAuth("/api/v1/users/me", unbound)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
    }

    @Test
    void groupAvatarRejectsOffOriginUrl() throws Exception {
        Registered owner = register(ItPhoneRanges.phoneFor(SecurityRound3IT.class, "+84924400013"));
        Registered member = register(ItPhoneRanges.phoneFor(SecurityRound3IT.class, "+84924400014"));
        var created = postJson("/api/v1/conversations/group",
                Map.of("title", "sec3", "memberIds", List.of(member.userId().toString())),
                owner.accessToken())
                .andExpect(status().isCreated())
                .andReturn();
        String conversationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("id").asText();

        patchWithAuth("/api/v1/conversations/" + conversationId,
                Map.of("avatarUrl", "https://evil.example/track.png"),
                owner.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void textPlainHtmlAndSvgUploadsAreRejected() throws Exception {
        Registered user = register(ItPhoneRanges.phoneFor(SecurityRound3IT.class, "+84924400015"));
        byte[] svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "x.svg", "text/plain", svg))
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("MIME_NOT_ALLOWED"));
        mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "x.html", "text/markdown", html))
                        .header("Authorization", "Bearer " + user.accessToken()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("MIME_NOT_ALLOWED"));
    }

    @Test
    void loginWithNulInPasswordDoesNotAuthenticate() throws Exception {
        register(ItPhoneRanges.phoneFor(SecurityRound3IT.class, "+84924400016"), "secret123");
        postJson("/api/v1/auth/login", Map.of(
                "phone", ItPhoneRanges.phoneFor(SecurityRound3IT.class, "+84924400016"),
                "password", "secret123\u0000ignored"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }
}
