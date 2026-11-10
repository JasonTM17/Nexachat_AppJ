package com.nexachat.robustness;

import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Assertions shared across hostile-input probes: never 5xx on the client path,
 * uniform error envelope, and no leaked implementation detail in the body.
 */
final class RobustnessResponseAssert {

    private static final String[] LEAK_MARKERS = {
            "at com.nexachat", "at org.springframework", "SQLException", "PSQLException",
            "org.hibernate", "Stack trace", "stackTrace", "Caused by:", "SELECT ", "INSERT "
    };

    private RobustnessResponseAssert() {
    }

    static ResultMatcher isSafeClientResponse() {
        return result -> {
            var response = result.getResponse();
            int status = response.getStatus();
            assertThat(status)
                    .as("HTTP status")
                    .isBetween(200, 599);
            assertThat(status)
                    .as("must not be 5xx")
                    .isLessThan(500);

            String body = response.getContentAsString();
            if (status >= 400) {
                assertThat(body)
                        .as("4xx responses must carry the error envelope")
                        .isNotBlank();
            }
            if (body != null && !body.isBlank()) {
                for (String marker : LEAK_MARKERS) {
                    assertThat(body).doesNotContainIgnoringCase(marker);
                }
                if (status >= 400) {
                    jsonPath("$.error.code").exists().match(result);
                    jsonPath("$.error.message").exists().match(result);
                }
            }
        };
    }

    /**
     * A response that must be a client rejection: the status is 4xx (not a
     * silently accepted 2xx) and the body is the shared error envelope.
     */
    static ResultMatcher isClientError() {
        return result -> {
            var response = result.getResponse();
            int status = response.getStatus();
            assertThat(status)
                    .as("HTTP status")
                    .isBetween(400, 499);

            String body = response.getContentAsString();
            assertThat(body)
                    .as("client errors must carry the error envelope")
                    .isNotBlank();
            for (String marker : LEAK_MARKERS) {
                assertThat(body).doesNotContainIgnoringCase(marker);
            }
            jsonPath("$.error.code").exists().match(result);
            jsonPath("$.error.message").exists().match(result);
        };
    }

    static ResultMatcher isClientErrorWithCode(String code) {
        return result -> {
            isClientError().match(result);
            jsonPath("$.error.code").value(code).match(result);
        };
    }
}
