package com.nexachat.auth;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * Shared per-account failed-sign-in budget (closes the known-limitations gap
 * where an account with both an email and a phone had one budget per claimed
 * credential).
 *
 * <p>Three layers bound password guessing on {@code POST /auth/login}:</p>
 * <ol>
 *   <li>per source address — {@link RateLimitInterceptor};</li>
 *   <li>per claimed credential — {@code AuthService#enforceCredentialRateLimit};</li>
 *   <li><b>this budget, one per account</b> — keyed by the locked row's canonical
 *       E.164 phone (verified email only for accounts without a phone), so
 *       email- and phone-path attempts against the same account drain ONE
 *       bucket even when the attacker rotates source addresses.</li>
 * </ol>
 *
 * <p>Every sign-in attempt that resolves to an account row charges the budget
 * before the bcrypt compare (a locked account never pays the hash cost); a
 * <b>successful</b> sign-in removes the bucket entirely, so failures only
 * accumulate across attempts that did not sign in. Exhaustion answers
 * {@code 429 SIGN_IN_LOCKED} with {@code details.retryAfterSeconds} and
 * {@code details.scope=account} (also mirrored into the {@code Retry-After}
 * header). The bucket refills greedily like every other limiter: after a drain,
 * one attempt is re-admitted every {@code window/maxFailures}, so a victim
 * whose account an attacker locks out can still sign in on the next admitted
 * attempt — which then resets the whole budget.</p>
 *
 * <p>Trade-offs, stated rather than hidden: the lockout response is
 * distinguishable from the uniform {@code INVALID_CREDENTIALS}, so a caller
 * that already burned the whole budget learns the account exists — the price
 * of every account-lockout control, and the response below the threshold stays
 * uniform. Buckets live in the in-memory {@link RateLimitService} map, so the
 * single-node caveats of that service apply: budgets reset on restart and do
 * not span instances. Denials are audited with a truncated SHA-256 key
 * reference — never the phone, email or any password material.</p>
 */
@Service
public class SignInFailureBudget {

    private static final Logger log = LoggerFactory.getLogger(SignInFailureBudget.class);

    /** Namespace so account buckets never collide with per-rule or per-IP keys. */
    static final String BUCKET_PREFIX = "signin-budget|";

    private static final int KEY_REF_HEX_CHARS = 16;

    private final RateLimitService rateLimitService;
    private final long maxFailures;
    private final Duration window;

    public SignInFailureBudget(RateLimitService rateLimitService, SignInBudgetProperties properties) {
        if (properties.getMaxFailures() < 1 || properties.getWindowSeconds() < 1) {
            throw new IllegalArgumentException(
                    "nexachat.auth.sign-in-budget max-failures and window-seconds must be positive");
        }
        this.rateLimitService = rateLimitService;
        this.maxFailures = properties.getMaxFailures();
        this.window = Duration.ofSeconds(properties.getWindowSeconds());
    }

    /**
     * Charges one sign-in attempt for the account. A successful sign-in must
     * follow with {@link #onSuccessfulSignIn} so the charge is refunded.
     *
     * @param accountKey the account's canonical budget key
     *                   ({@code phone|<e164>} or {@code email|<address>})
     * @throws ApiException {@code 429 SIGN_IN_LOCKED} once the budget is
     *                      exhausted, with {@code retryAfterSeconds} and
     *                      {@code scope} in the details
     */
    public void chargeAttempt(String accountKey) {
        RateLimitService.Consumption consumption =
                rateLimitService.tryConsume(BUCKET_PREFIX + accountKey, maxFailures, window);
        if (!consumption.allowed()) {
            log.warn("Sign-in budget exhausted (accountKeyRef={}, retryAfterSeconds={})",
                    keyRef(accountKey), consumption.retryAfterSeconds());
            throw ApiException.of(
                    ApiErrorCode.SIGN_IN_LOCKED,
                    "Too many failed sign-in attempts, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds(), "scope", "account"));
        }
    }

    /** A successful sign-in resets the account's budget to a full window. */
    public void onSuccessfulSignIn(String accountKey) {
        rateLimitService.resetBucket(BUCKET_PREFIX + accountKey);
    }

    /**
     * Stable, non-reversible reference for audit logs: the first bytes of the
     * account key's SHA-256. Operators can correlate lockouts of one account
     * without the phone or email address ever reaching the log.
     */
    private static String keyRef(String accountKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(accountKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, KEY_REF_HEX_CHARS / 2)
                    .toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA specification; unreachable.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
