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

    @Test
    void timeZoneIsOptionalAndOldBodiesStillParse() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        SummaryRequest old = mapper.readValue("{\"scope\":\"TODAY\"}", SummaryRequest.class);
        SummaryRequest current = mapper.readValue(
                "{\"scope\":\"TODAY\",\"timeZone\":\"Asia/Tokyo\"}", SummaryRequest.class);

        assertThat(old.timeZone()).isNull();
        assertThat(current.timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(validator.validate(current)).isEmpty();
    }
}
