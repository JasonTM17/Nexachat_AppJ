package com.nexachat.translate;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction boundaries on the translate paths. The provider chain is a
 * blocking external HTTP call, so {@code translate} and {@code translateBatch}
 * must never hold a database transaction — otherwise one slow provider pins a
 * pooled connection for up to the combined LLM timeouts and a batch can starve
 * the whole node. The cache write they finish with must instead be atomic in
 * its own transaction, and the pure read endpoints stay readOnly.
 */
class TranslateServiceTxContractTest {

    @Test
    void translateAndBatchKeepTheProviderCallOutsideAnyTransaction() throws Exception {
        for (String name : new String[]{"translate", "translateBatch"}) {
            Method method = findMethod(TranslateService.class, name);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("@Transactional on %s (provider HTTP must run outside a tx)",
                    name).isNull();
        }
    }

    @Test
    void cacheSaveIsAtomicInItsOwnTransaction() throws Exception {
        Method method = findMethod(TranslationCacheRepository.class, "save");
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).as("@Transactional on TranslationCacheRepository.save").isNotNull();
        assertThat(tx.readOnly()).as("cache save must not be readOnly").isFalse();
    }

    @Test
    void writeEndpointsStayReadWriteTransactions() throws Exception {
        for (String name : new String[]{"replaceGlossary", "updatePrefs"}) {
            Method method = findMethod(TranslateService.class, name);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("@Transactional on %s", name).isNotNull();
            assertThat(tx.readOnly()).as("%s must not be readOnly", name).isFalse();
        }
    }

    @Test
    void pureReadsStayReadOnly() throws Exception {
        for (String name : new String[]{"getGlossary", "getPrefs"}) {
            Method method = findMethod(TranslateService.class, name);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("@Transactional on %s", name).isNotNull();
            assertThat(tx.readOnly()).as("%s should stay readOnly", name).isTrue();
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("no method " + name);
    }
}
