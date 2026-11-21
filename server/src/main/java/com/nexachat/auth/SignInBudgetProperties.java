package com.nexachat.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the shared per-account failed-sign-in budget.
 *
 * <p>Env overrides: {@code NEXACHAT_AUTH_SIGN_IN_BUDGET_MAX_FAILURES} and
 * {@code NEXACHAT_AUTH_SIGN_IN_BUDGET_WINDOW_SECONDS}. Values below 1 fail
 * startup (validated in {@link SignInFailureBudget}) instead of silently
 * disabling the budget.
 */
@ConfigurationProperties(prefix = "nexachat.auth.sign-in-budget")
public class SignInBudgetProperties {

    /**
     * Failed sign-in attempts one account admits inside the window before its
     * budget answers {@code 429 SIGN_IN_LOCKED}. Default is deliberately below
     * the per-claimed-credential budget (20 per 5 min) so the shared account
     * budget is the tighter bound for a distributed guess against one account.
     */
    private int maxFailures = 10;

    /** Sliding-refill window in seconds; the budget refills greedily across it. */
    private long windowSeconds = 900;

    public int getMaxFailures() {
        return maxFailures;
    }

    public void setMaxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
