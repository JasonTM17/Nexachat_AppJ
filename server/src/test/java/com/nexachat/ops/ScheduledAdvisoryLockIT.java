package com.nexachat.ops;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("it")
class ScheduledAdvisoryLockIT {

    private static final long TEST_LOCK = 2_530_099L;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test"));
        registry.add("spring.datasource.username", () -> env("IT_DB_USER", "nexachat"));
        registry.add("spring.datasource.password", () -> env("IT_DB_PASSWORD", "nexachat"));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private ScheduledAdvisoryLock advisoryLock;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    @AfterEach
    void clearHeldLocks() {
        jdbcTemplate.execute("SELECT pg_advisory_unlock_all()");
    }

    @Test
    void onlyOneConnectionAcquiresTheLockConcurrently() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return advisoryLock.tryAcquire(TEST_LOCK);
            });
            Future<Boolean> second = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return advisoryLock.tryAcquire(TEST_LOCK);
            });
            start.countDown();

            boolean acquiredFirst = first.get(5, TimeUnit.SECONDS);
            boolean acquiredSecond = second.get(5, TimeUnit.SECONDS);
            assertThat(acquiredFirst ^ acquiredSecond).isTrue();
            if (acquiredFirst) {
                advisoryLock.release(TEST_LOCK);
            }
            if (acquiredSecond) {
                advisoryLock.release(TEST_LOCK);
            }
        }
    }
}
