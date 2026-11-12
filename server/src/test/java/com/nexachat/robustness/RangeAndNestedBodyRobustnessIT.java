package com.nexachat.robustness;

import com.nexachat.testutil.ItPhoneRanges;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Byte-range and parser-depth probes.
 *
 * <ul>
 *   <li>Raw {@code /api/v1/files/**} downloads must answer every hostile
 *       {@code Range} header with a bounded 206/416, never a 5xx, a stack
 *       trace, or a hang.</li>
 *   <li>Deeply nested JSON on every JSON POST body must be rejected by the
 *       Jackson depth constraint as a clean 400, not a stack overflow.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RangeAndNestedBodyRobustnessIT extends RobustnessITBase {

    private static final byte[] PNG_BYTES = hex("89504E470D0A1A0A"
            + "0000000D4948445200000010000000100806000000");

    /** Far above Jackson's default nesting limit of 1000, still under the 1 MiB body cap. */
    private static final int NESTING_DEPTH = 100_000;

    private Registered alice;
    private String filesUrl;
    private UUID conversationId;

    @BeforeAll
    void seed() throws Exception {
        resetSharedFixtureDatabase();
        alice = register(ItPhoneRanges.phoneFor(RangeAndNestedBodyRobustnessIT.class, "+84907600001"), "Range Fuzz");
        Registered peer = register(ItPhoneRanges.phoneFor(RangeAndNestedBodyRobustnessIT.class, "+84907600002"), "Range Peer");
        conversationId = openDirect(alice, peer.userId()).id();

        MvcResult uploaded = mockMvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "range.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andReturn();
        assertThat(uploaded.getResponse().getStatus()).isEqualTo(201);
        filesUrl = objectMapper.readTree(uploaded.getResponse().getContentAsString())
                .get("url").asText();
    }

    // --------------------------------------------------------------- ranges

    static Stream<Arguments> hostileAndValidRanges() {
        int size = PNG_BYTES.length;
        return Stream.of(
                // Malformed / wrong unit / empty spec.
                Arguments.of("bytes=abc-def", 416, null),
                Arguments.of("items=0-1", 416, null),
                Arguments.of("bytes=", 416, null),
                Arguments.of("bytes=1", 416, null),
                Arguments.of("bytes=-", 416, null),
                Arguments.of("bytes=--1", 416, null),
                Arguments.of("bytes=0-1-2", 416, null),
                Arguments.of("bytes=,", 416, null),
                Arguments.of("bytes=,0-0", 416, null),
                Arguments.of("bytes=-0", 416, null),
                // Unsatisfiable / inverted / overflow.
                Arguments.of("bytes=5-3", 416, null),
                Arguments.of("bytes=" + size + "-", 416, null),
                Arguments.of("bytes=999999-1000000", 416, null),
                Arguments.of("bytes=" + Long.MAX_VALUE + "-", 416, null),
                Arguments.of("bytes=9223372036854775808-", 416, null),
                Arguments.of("bytes=0-9223372036854775808", 416, null),
                // Beyond length: clamped, not an error.
                Arguments.of("bytes=0-999999", 206, new int[] {0, size - 1}),
                Arguments.of("bytes=-999999", 206, new int[] {0, size - 1}),
                // Multiple ranges: only the first is honoured.
                Arguments.of("bytes=0-1,4-5", 206, new int[] {0, 1}),
                Arguments.of("bytes=0-3,2-5", 206, new int[] {0, 3}), // overlapping
                Arguments.of("bytes=0-1,abc", 206, new int[] {0, 1}),  // later spec ignored
                Arguments.of("bytes=0-0,", 206, new int[] {0, 0}),
                // Legal syntax variants.
                Arguments.of("Bytes=0-0", 206, new int[] {0, 0}),
                Arguments.of("bytes= 0-0 ", 206, new int[] {0, 0}),
                Arguments.of("bytes=0 - 0", 206, new int[] {0, 0}),
                Arguments.of("bytes=" + (size - 1) + "-" + (size - 1),
                        206, new int[] {size - 1, size - 1})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileAndValidRanges")
    void rawFileDownloadRangeEdgeCases(String range, int expectedStatus, int[] expectedSlice)
            throws Exception {
        MvcResult result = assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                mockMvc.perform(get(filesUrl)
                                .header("Authorization", "Bearer " + alice.accessToken())
                                .header(HttpHeaders.RANGE, range))
                        .andReturn());

        var response = result.getResponse();
        assertThat(response.getStatus())
                .as("range %s", range)
                .isEqualTo(expectedStatus)
                .isLessThan(500);
        assertThat(response.getContentAsString())
                .doesNotContain("at com.nexachat", "SQLException", "PSQLException", "Caused by:");
        assertThat(response.getHeader(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");

        if (expectedStatus == 416) {
            assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE))
                    .isEqualTo("bytes */" + PNG_BYTES.length);
            assertThat(response.getContentAsByteArray()).isEmpty();
            return;
        }

        int start = expectedSlice[0];
        int end = expectedSlice[1];
        assertThat(response.getHeader(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes " + start + "-" + end + "/" + PNG_BYTES.length);
        assertThat(response.getHeader(HttpHeaders.CONTENT_LENGTH))
                .isEqualTo(String.valueOf(end - start + 1));
        assertThat(response.getContentAsByteArray())
                .containsExactly(Arrays.copyOfRange(PNG_BYTES, start, end + 1));
    }

    // ------------------------------------------------------- JSON nesting

    static Stream<Arguments> deeplyNestedJsonPosts() {
        String nested = "[".repeat(NESTING_DEPTH) + "]".repeat(NESTING_DEPTH);
        String messageBody = "{\"clientMessageId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"TEXT\",\"content\":" + nested + "}";
        return Stream.of(
                Arguments.of("/api/v1/conversations/{conversationId}/messages",
                        messageBody, true, "messages"),
                Arguments.of("/api/v1/sync",
                        "{\"watermarks\":" + nested + "}", true, "sync"),
                Arguments.of("/api/v1/chat/sync",
                        "{\"watermarks\":" + nested + "}", true, "chat-sync"),
                Arguments.of("/api/v1/auth/login",
                        "{\"phone\":" + nested + ",\"password\":\"secret123\"}", false, "login"),
                Arguments.of("/api/v1/auth/register",
                        "{\"phone\":" + nested + ",\"password\":\"secret123\",\"displayName\":\"x\"}",
                        false, "register"),
                Arguments.of("/api/v1/auth/refresh",
                        "{\"refreshToken\":" + nested + "}", false, "refresh")
        );
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("deeplyNestedJsonPosts")
    void absurdJsonNestingIsRejectedWithoutStackOverflow(String path, String body,
                                                          boolean authenticated, String label)
            throws Exception {
        long messagesBefore = messageCount(conversationId);

        MvcResult result = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            MockHttpServletRequestBuilder request = post(path.replace("{conversationId}",
                    conversationId.toString()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
            if (authenticated) {
                request.header("Authorization", "Bearer " + alice.accessToken());
            }
            return mockMvc.perform(request).andReturn();
        });

        var response = result.getResponse();
        assertThat(response.getStatus())
                .as("%s nested body", label)
                .isEqualTo(400);
        assertThat(objectMapper.readTree(response.getContentAsString())
                .path("error").path("code").asText())
                .as("%s error code", label)
                .isEqualTo("VALIDATION_FAILED");
        assertThat(response.getContentAsString())
                .doesNotContain("at com.nexachat", "StackOverflow", "StreamConstraintsException",
                        "Caused by:");
        assertThat(messageCount(conversationId)).isEqualTo(messagesBefore);
    }

    private static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
