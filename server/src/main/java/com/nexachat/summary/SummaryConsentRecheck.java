package com.nexachat.summary;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.summary.SummaryConsentRepository.ConsentSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Post-generation consent re-check for the external summary lane. The
 * response is suppressed when the snapshot moved between the window read
 * and the store: the check takes a shared row lock via
 * {@link SummaryConsentRepository#lockForResponse(UUID)} and re-reads the
 * snapshot, so a consent revoke, which uses a conflicting write lock on the
 * same row, either commits before this lock (the snapshot differs) or after
 * it (the response was already serialized). The check is one locked SELECT:
 * no extra LLM call.
 *
 * <p>Only the external lane is checked: a local-tier generation never sent
 * text outside, so a mid-flight consent flip must not discard it. A
 * negative epoch is the repository's missing-row sentinel (the column
 * itself is CHECK >= 0): the account was deleted mid-flight, which the
 * cache write's own requester lock already reports as USER_NOT_FOUND, so
 * only a real snapshot change suppresses here.
 */
/**
 * Production bean only: the {@code test} profile wires no transaction
 * manager, and the service itself is gated the same way. Tests that
 * exercise the re-check construct the class directly; ones that do not need
 * it use {@link #noop()}.
 */
@Component
@Profile("!test")
public class SummaryConsentRecheck {

    private static final SummaryConsentRecheck NOOP =
            new SummaryConsentRecheck(null, (TransactionTemplate) null);

    private final SummaryConsentRepository consentRepository;
    private final TransactionTemplate tx;

    @Autowired
    public SummaryConsentRecheck(
            SummaryConsentRepository consentRepository,
            PlatformTransactionManager transactionManager) {
        this.consentRepository = consentRepository;
        this.tx = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    private SummaryConsentRecheck(SummaryConsentRepository consentRepository, TransactionTemplate tx) {
        this.consentRepository = consentRepository;
        this.tx = tx;
    }

    /**
     * Explicit no-op for tests that do not exercise the consent race —
     * production always wires the real bean.
     */
    public static SummaryConsentRecheck noop() {
        return NOOP;
    }

    /**
     * Throws AI_CONSENT_REQUIRED when the locked snapshot differs from the
     * one captured at window read. Only meaningful on the external lane;
     * local-tier generations and sentinel (negative-epoch) reads return
     * immediately.
     */
    public void verifyUnchanged(UUID userId, String generatedBy, ConsentSnapshot atRead) {
        if (tx == null || !"LLM".equals(generatedBy) || atRead.epoch() < 0) {
            return;
        }
        tx.executeWithoutResult(status -> {
            ConsentSnapshot current = consentRepository.lockForResponse(userId);
            if (current.epoch() >= 0 && !atRead.equals(current)) {
                throw ApiException.of(ApiErrorCode.AI_CONSENT_REQUIRED,
                        "AI summary consent changed while the summary was being generated");
            }
        });
    }
}
