package com.nexachat.summary;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void lastNRequiresLimitWithinInclusiveBounds() {
        assertThat(validator.validate(new SummaryRequest(SummaryScope.LAST_N, 4, null, null))).isNotEmpty();
        assertThat(validator.validate(new SummaryRequest(SummaryScope.LAST_N, 501, null, null))).isNotEmpty();
        assertThat(validator.validate(new SummaryRequest(SummaryScope.LAST_N, 5, null, null))).isEmpty();
        assertThat(validator.validate(new SummaryRequest(SummaryScope.LAST_N, 500, null, null))).isEmpty();
    }

    @Test
    void otherScopesDoNotRequireLastNLimitAndDefaultsAreApplied() {
        SummaryRequest request = new SummaryRequest(SummaryScope.UNREAD, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
        assertThat(request.language()).isEqualTo(SummaryLanguage.AUTO);
        assertThat(request.refresh()).isFalse();
    }
}
