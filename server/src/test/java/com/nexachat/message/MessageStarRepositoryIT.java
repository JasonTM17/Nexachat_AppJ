package com.nexachat.message;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

@SpringBootTest
@ActiveProfiles("it")
class MessageStarRepositoryIT {

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
    private MessageStarRepository starRepository;

    @Test
    void findStarredRefsFirstPageRuns() {
        starRepository.findStarredRefsFirst(UUID.randomUUID(), PageRequest.of(0, 10));
        starRepository.findStarredRefsAfter(UUID.randomUUID(), Instant.now(), UUID.randomUUID(),
                PageRequest.of(0, 10));
    }
}
