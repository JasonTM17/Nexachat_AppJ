package com.nexachat.rag;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression gate for the RAG baseline recorded in PR #205. Runs the exact
 * measurement of {@link RagBenchmarkEvaluationTest} (reused via reflection —
 * that file is read-only for this lane) and FAILS when any metric drops below
 * the recorded baseline. Improvements pass; only regressions fail.
 *
 * Recorded baseline (PR #205, verbatim):
 * recall@3=1.000 recall@5=1.000 mrr=1.000 citationValidity=1.000
 * zeroLlm=70.0% meanInputChars=1640.6 reduction=79.4%
 * (32 conversations, 6400 messages, 192 lookup questions).
 */
class RagBaselineGuardTest {

    private static final double BASELINE_RECALL_AT_3 = 1.000;
    private static final double BASELINE_RECALL_AT_5 = 1.000;
    private static final double BASELINE_MRR = 1.000;
    private static final double BASELINE_CITATION_VALIDITY = 1.000;
    private static final double MIN_ZERO_LLM_RATIO = 0.699;   // recorded 0.700
    private static final double MIN_REDUCTION_PERCENT = 79.0;  // recorded 79.4%
    private static final int MIN_LOOKUP_QUESTIONS = 150;

    @Test
    void ragMetricsDoNotRegressBelowRecordedBaseline() throws Exception {
        Class<?> corpusType = Class.forName(
                "com.nexachat.rag.RagBenchmarkEvaluationTest$SyntheticCorpus");
        Object corpus = invokeStatic(corpusType, "generate");
        Object index = invoke(corpus, "index");
        RagRetrievalService retrieval = RagTestSupport.retrieval(
                (InMemoryRagMessageIndex) index, new RagProperties());

        RagBenchmarkEvaluationTest benchmark = new RagBenchmarkEvaluationTest();
        Object retrievalMetrics = invoke(benchmark, "evaluateRetrieval",
                new Class<?>[] {corpusType, RagRetrievalService.class}, corpus, retrieval);
        Object routerMetrics = invoke(benchmark, "evaluateRouter",
                new Class<?>[] {corpusType}, corpus);

        double recallAt3 = metric(retrievalMetrics, "recallAt3");
        double recallAt5 = metric(retrievalMetrics, "recallAt5");
        double mrr = metric(retrievalMetrics, "mrr");
        double citationValidity = metric(retrievalMetrics, "citationValidity");
        double zeroLlmRatio = metric(routerMetrics, "zeroLlmRatio");
        double meanInputChars = metric(routerMetrics, "meanInputChars");
        double meanBaselineChars = metric(routerMetrics, "meanBaselineChars");
        double reductionPercent = metric(routerMetrics, "reductionPercent");

        @SuppressWarnings("unchecked")
        List<Object> lookupQuestions = (List<Object>) invoke(corpus, "lookupQuestions");
        @SuppressWarnings("unchecked")
        Map<UUID, Set<UUID>> messageIds = (Map<UUID, Set<UUID>>) invoke(corpus, "messageIds");
        int conversations = messageIds.size();
        int messages = messageIds.values().stream().mapToInt(Set::size).sum();

        System.out.printf(
                "rag-eval: conversations=%d messages=%d lookupQuestions=%d "
                        + "recall@3=%.3f recall@5=%.3f mrr=%.3f citationValidity=%.3f "
                        + "zeroLlm=%.1f%% meanInputChars=%.1f baselineChars=%.1f reduction=%.1f%%%n",
                conversations,
                messages,
                lookupQuestions.size(),
                recallAt3,
                recallAt5,
                mrr,
                citationValidity,
                zeroLlmRatio * 100.0,
                meanInputChars,
                meanBaselineChars,
                reductionPercent);

        assertThat(lookupQuestions.size()).isGreaterThanOrEqualTo(MIN_LOOKUP_QUESTIONS);
        assertThat(recallAt3).isGreaterThanOrEqualTo(BASELINE_RECALL_AT_3);
        assertThat(recallAt5).isGreaterThanOrEqualTo(BASELINE_RECALL_AT_5);
        assertThat(mrr).isGreaterThanOrEqualTo(BASELINE_MRR);
        assertThat(citationValidity).isEqualTo(BASELINE_CITATION_VALIDITY);
        assertThat(zeroLlmRatio).isGreaterThanOrEqualTo(MIN_ZERO_LLM_RATIO);
        assertThat(reductionPercent).isGreaterThanOrEqualTo(MIN_REDUCTION_PERCENT);
    }

    private static Object invokeStatic(Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes,
                                 Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static double metric(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (Double) method.invoke(record);
    }
}
