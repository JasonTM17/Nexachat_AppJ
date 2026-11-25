package com.nexachat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.summary.ConversationAiOptOuts;
import com.nexachat.summary.SummaryConsentRepository;
import com.nexachat.summary.llm.CircuitBreaker;
import com.nexachat.summary.llm.LlmClient;
import com.nexachat.summary.llm.Redactor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RagLlmAnswerServiceWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WiringConfiguration.class);

    @Test
    void serviceWiresTheDocumentedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RagLlmAnswerService.class);
            RagLlmAnswerService service = context.getBean(RagLlmAnswerService.class);
            Semaphore bulkhead = (Semaphore) ReflectionTestUtils.getField(service, "llmBulkhead");
            assertThat(bulkhead).isNotNull();
            assertThat(bulkhead.availablePermits()).isEqualTo(8);
            assertThat(ReflectionTestUtils.getField(service, "bulkheadWaitMs")).isEqualTo(500L);
        });
    }

    @Test
    void serviceWiresConfiguredBulkheadProperties() {
        runner.withPropertyValues(
                        "nexachat.rag.llm.max-concurrent=2",
                        "nexachat.rag.llm.bulkhead-wait-ms=25")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RagLlmAnswerService.class);
                    RagLlmAnswerService service = context.getBean(RagLlmAnswerService.class);
                    Semaphore bulkhead = (Semaphore) ReflectionTestUtils.getField(service, "llmBulkhead");
                    assertThat(bulkhead).isNotNull();
                    assertThat(bulkhead.availablePermits()).isEqualTo(2);
                    assertThat(ReflectionTestUtils.getField(service, "bulkheadWaitMs")).isEqualTo(25L);
                });
    }

    @Test
    void applicationYamlReadsBulkheadValuesFromTheirEnvironmentVariables() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertThat(application.getProperty("nexachat.rag.llm.max-concurrent"))
                .isEqualTo("${NEXACHAT_RAG_LLM_MAX_CONCURRENT:8}");
        assertThat(application.getProperty("nexachat.rag.llm.bulkhead-wait-ms"))
                .isEqualTo("${NEXACHAT_RAG_LLM_BULKHEAD_WAIT_MS:500}");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RagProperties.class)
    @Import(RagLlmAnswerService.class)
    static class WiringConfiguration {

        @Bean
        LlmClient llmClient() {
            return mock(LlmClient.class);
        }

        @Bean
        RagPromptBuilder ragPromptBuilder() {
            return new RagPromptBuilder(new ObjectMapper(), new Redactor());
        }

        @Bean
        RagOutputValidator ragOutputValidator() {
            return new RagOutputValidator(new ObjectMapper());
        }

        @Bean
        CircuitBreaker ragCircuitBreaker() {
            return new CircuitBreaker(Clock.systemUTC());
        }

        @Bean
        SummaryConsentRepository summaryConsentRepository() {
            return mock(SummaryConsentRepository.class);
        }

        @Bean
        RagStats ragStats() {
            return new RagStats();
        }

        @Bean
        ConversationAiOptOuts conversationAiOptOuts() {
            return ConversationAiOptOuts.none();
        }
    }
}
