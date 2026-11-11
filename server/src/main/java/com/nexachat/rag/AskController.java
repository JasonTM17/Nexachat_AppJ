package com.nexachat.rag;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/conversations")
public class AskController {

    private final AskService askService;
    private final AskResponseWriter responseWriter;

    public AskController(AskService askService, AskResponseWriter responseWriter) {
        this.askService = askService;
        this.responseWriter = responseWriter;
    }

    @PostMapping("/{conversationId}/ask")
    public void ask(
            @AuthenticationPrincipal JwtPrincipal principal,
            @PathVariable UUID conversationId,
            @Valid @RequestBody AskRequest request,
            HttpServletResponse response) throws IOException {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        AskExecution execution = askService.askForResponse(conversationId, principal.userId(), request);
        responseWriter.write(principal.userId(), execution, response);
    }
}
