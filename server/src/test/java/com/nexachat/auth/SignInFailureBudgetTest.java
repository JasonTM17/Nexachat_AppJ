package com.nexachat.auth;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.testutil.MutableTimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The per-account failed-sign-in budget: one bucket per account key, drained
 * by attempts that did not sign in, reset by a successful sign-in, and
 * exhausted with a distinct {@code 429 SIGN_IN_LOCKED} contract.
 *
 * <p>Uses a frozen {@link MutableTimeMeter} like {@link RateLimitServiceTest}
 * so the greedy refill never moves while an assertion is in flight.
 */
class SignInFailureBudgetTest {

    private final MutableTimeMeter meter = new MutableTimeMeter();
    private final RateLimitService rateLimitService =
            new RateLimitService(10_000, Duration.ofMinutes(15), meter);

    private SignInFailureBudget budget(int maxFailures, long windowSeconds) {
        SignInBudgetProperties properties = new SignInBudgetProperties();
        properties.setMaxFailures(maxFailures);
        properties.setWindowSeconds(windowSeconds);
        return new SignInFailureBudget(rateLimitService, properties);
    }

    @Test
    void admitsUpToMaxFailuresThenLocksWithAccountScopedContract() {
        SignInFailureBudget budget = budget(3, 900);
        String account = "phone|+84912345678";

        budget.chargeAttempt(account);
        budget.chargeAttempt(account);
        budget.chargeAttempt(account);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> budget.chargeAttempt(account));
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.SIGN_IN_LOCKED);
        assertThat(ex.getHttpStatus()).isEqualTo(429);
        assertThat(ex.getDetails()).containsEntry("scope", "account");
        assertThat(ex.getDetails().get("retryAfterSeconds"))
                .as("the client is told how long to back off")
                .isInstanceOf(Long.class)
                .satisfies(seconds -> assertThat((Long) seconds).isPositive());
    }

    @Test
    void aFrozenClockDeniesExactlyAndRefillReAdmitsOneAttemptAtATime() {
        SignInFailureBudget budget = budget(2, 900);
        String account = "phone|+84912345678";

        budget.chargeAttempt(account);
        budget.chargeAttempt(account);
        assertThatThrownBy(() -> budget.chargeAttempt(account))
                .isInstanceOf(ApiException.class);

        // Greedy refill: window/2 = 450 s per token, so the drained budget
        // re-admits exactly one attempt per 450 s while the clock is frozen.
        meter.advance(Duration.ofSeconds(450));
        budget.chargeAttempt(account);
        assertThatThrownBy(() -> budget.chargeAttempt(account))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aSuccessfulSignInRestoresTheFullBudget() {
        SignInFailureBudget budget = budget(2, 900);
        String account = "phone|+84912345678";

        budget.chargeAttempt(account);
        budget.chargeAttempt(account);
        assertThatThrownBy(() -> budget.chargeAttempt(account))
                .isInstanceOf(ApiException.class);

        budget.onSuccessfulSignIn(account);

        // The whole window is available again, not just the trickle.
        budget.chargeAttempt(account);
        budget.chargeAttempt(account);
        assertThatThrownBy(() -> budget.chargeAttempt(account))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void resettingAnUnknownKeyIsANoOpAndAccountKeysStayIndependent() {
        SignInFailureBudget budget = budget(2, 900);
        String attacked = "phone|+84912345678";
        String neighbour = "phone|+84987654321";

        budget.onSuccessfulSignIn("phone|+84000000000");

        budget.chargeAttempt(attacked);
        budget.chargeAttempt(attacked);
        assertThatThrownBy(() -> budget.chargeAttempt(attacked))
                .isInstanceOf(ApiException.class);

        // Another account's budget is untouched by the attack.
        budget.chargeAttempt(neighbour);
        budget.chargeAttempt(neighbour);
        assertThatThrownBy(() -> budget.chargeAttempt(neighbour))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void nonPositiveConfigurationFailsStartupInsteadOfDisablingTheBudget() {
        assertThatThrownBy(() -> budget(0, 900))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget(5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
