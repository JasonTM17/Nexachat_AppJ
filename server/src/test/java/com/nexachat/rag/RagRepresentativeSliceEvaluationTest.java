package com.nexachat.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RagRepresentativeSliceEvaluationTest {

    @Test
    void developmentSliceMeetsRetrievalAndAbstentionTargets() throws Exception {
        var report = RagRepresentativeEvaluation.run(RagRepresentativeCorpus.load("dev"));
        report.print();

        assertSoftly(softly -> {
            softly.assertThat(report.recallAt(5)).as("Recall@5").isGreaterThanOrEqualTo(0.90);
            softly.assertThat(report.reciprocalRank()).as("MRR").isGreaterThanOrEqualTo(0.85);
            softly.assertThat(report.citationValidity()).as("citation validity").isGreaterThanOrEqualTo(0.95);
            softly.assertThat(report.zeroLlm()).as("requests without simulated LLM routing").isGreaterThanOrEqualTo(0.80);
            softly.assertThat(report.negatives()).filteredOn(result -> !result.abstained())
                    .extracting(result -> result.question().id()).as("negative requests that did not abstain").isEmpty();
            softly.assertThat(report.cases()).filteredOn(RagRepresentativeEvaluation.CaseResult::scopeViolation)
                    .extracting(result -> result.question().id()).as("out-of-scope evidence").isEmpty();
        });
    }
}
