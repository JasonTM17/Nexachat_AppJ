package com.nexachat.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagHoldoutSliceEvaluationTest {

    @Test
    void reportDisjointHoldoutWithoutQualityThresholds() throws Exception {
        RagRepresentativeCorpus holdout = RagRepresentativeCorpus.load("holdout");
        holdout.assertDisjointFrom(RagRepresentativeCorpus.load("dev"));
        var report = RagRepresentativeEvaluation.run(holdout);
        report.print();

        assertThat(report.cases()).filteredOn(RagRepresentativeEvaluation.CaseResult::scopeViolation)
                .extracting(result -> result.question().id()).as("out-of-scope evidence").isEmpty();
    }
}
