package com.nexachat.translate;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Translate write paths must never run inside a read-only transaction:
 * cache rows are INSERTed on provider misses, and PostgreSQL rejects writes
 * under {@code SET TRANSACTION READ ONLY}. The read endpoints stay readOnly.
 */
class TranslateServiceTxContractTest {

    @Test
    void translateAndBatchAreReadWriteTransactions() throws Exception {
        for (String name : new String[]{"translate", "translateBatch", "replaceGlossary",
                "updatePrefs"}) {
            Method method = findMethod(name);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("@Transactional on %s", name).isNotNull();
            assertThat(tx.readOnly()).as("%s must not be readOnly", name).isFalse();
        }
    }

    @Test
    void pureReadsStayReadOnly() throws Exception {
        for (String name : new String[]{"getGlossary", "getPrefs"}) {
            Method method = findMethod(name);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(tx).as("@Transactional on %s", name).isNotNull();
            assertThat(tx.readOnly()).as("%s should stay readOnly", name).isTrue();
        }
    }

    private static Method findMethod(String name) {
        for (Method method : TranslateService.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new IllegalStateException("no method " + name);
    }
}
