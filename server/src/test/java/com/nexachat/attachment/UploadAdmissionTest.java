package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class UploadAdmissionTest {

    @Test
    void acquire_beyondLimit_answersRateLimitedWithRetryAfter() {
        UploadAdmission admission = new UploadAdmission(1, 50);
        UploadAdmission.Permit held = admission.acquire();
        try {
            ApiException rejection = catchThrowableOfType(ApiException.class, admission::acquire);

            assertThat(rejection.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
            assertThat(rejection.getHttpStatus()).isEqualTo(429);
            assertThat(rejection.getDetails()).containsEntry("retryAfterSeconds", 1L);
        } finally {
            held.close();
        }
    }

    @Test
    void close_returnsTheSlot() {
        UploadAdmission admission = new UploadAdmission(1, 0);

        admission.acquire().close();

        assertThat(admission.availablePermits()).isEqualTo(1);
        admission.acquire().close();
        assertThat(admission.availablePermits()).isEqualTo(1);
    }

    @Test
    void close_isIdempotent() {
        UploadAdmission admission = new UploadAdmission(1, 0);

        UploadAdmission.Permit permit = admission.acquire();
        permit.close();
        permit.close();

        assertThat(admission.availablePermits()).isEqualTo(1);
    }

    @Test
    void acquire_afterInterrupt_rejectsAndPreservesTheFlag() {
        UploadAdmission admission = new UploadAdmission(1, 10_000);
        UploadAdmission.Permit held = admission.acquire();
        try {
            Thread.currentThread().interrupt();

            ApiException rejection = catchThrowableOfType(ApiException.class, admission::acquire);

            assertThat(rejection.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear the flag before the test thread is reused by the runner.
            Thread.interrupted();
            held.close();
        }
    }

    @Test
    void constructor_rejectsNonPositiveLimit() {
        assertThat(catchThrowableOfType(IllegalArgumentException.class,
                () -> new UploadAdmission(0, 100)))
                .hasMessageContaining("max-concurrent");
        assertThat(catchThrowableOfType(IllegalArgumentException.class,
                () -> new UploadAdmission(1, -1)))
                .hasMessageContaining("acquire-timeout-ms");
    }
}
