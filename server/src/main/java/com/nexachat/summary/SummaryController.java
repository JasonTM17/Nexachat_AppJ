package com.nexachat.summary;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.io.IOException;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/conversations")
public class SummaryController {

    private final SummaryService summaryService;
    private final SummaryResponseWriter responseWriter;

    public SummaryController(SummaryService summaryService, SummaryResponseWriter responseWriter) {
        this.summaryService = summaryService;
        this.responseWriter = responseWriter;
    }

    @PostMapping("/{conversationId}/summary")
    public void summarize(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SummaryRequest request,
            HttpServletResponse response) throws IOException {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        SummaryExecution execution = summaryService.summarizeForResponse(conversationId, principal.userId(), request);
        responseWriter.write(principal.userId(), execution, response);
    }
}
