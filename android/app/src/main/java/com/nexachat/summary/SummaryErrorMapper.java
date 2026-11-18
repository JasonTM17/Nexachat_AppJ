package com.nexachat.summary;

import com.nexachat.core.network.dto.ErrorResponse;
import java.io.IOException;

final class SummaryErrorMapper {
    private SummaryErrorMapper() { }

    static SummaryError fromHttp(int status, String errorBody) {
        String code = ErrorResponse.errorCodeOf(errorBody);
        if (status == 429 || "RATE_LIMITED".equals(code)) {
            return SummaryError.RATE_LIMITED;
        }
        if (status == 403 || "NOT_MEMBER".equals(code)) {
            return SummaryError.NOT_MEMBER;
        }
        if ("E2EE_AI_UNAVAILABLE".equals(code)) {
            return SummaryError.E2EE_UNAVAILABLE;
        }
        if ("INVALID_TIME_ZONE".equals(code)) {
            return SummaryError.INVALID_TIME_ZONE;
        }
        if ("TOO_FEW_MESSAGES".equals(code) || "TOO_FEW".equals(code)) {
            return SummaryError.TOO_FEW;
        }
        return SummaryError.UNKNOWN;
    }

    static SummaryError fromFailure(Throwable failure) {
        return failure instanceof IOException ? SummaryError.NETWORK : SummaryError.UNKNOWN;
    }
}
