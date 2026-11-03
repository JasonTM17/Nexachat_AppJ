package com.nexachat.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.dto.ErrorResponse;
import com.nexachat.common.error.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Writes the standard error envelope for security-layer failures, so a missing
 * or bad Bearer token produces the same {@code {"error":{...}}} shape as
 * application errors instead of Spring's default HTML/empty body.
 *
 * <p>When {@link JwtAuthFilter} stashed a precise code (expired/invalid), that
 * code wins over the generic {@code UNAUTHORIZED}.
 */
@Component
public class AuthSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public AuthSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object code = request.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTRIBUTE);
        if (code == ApiErrorCode.TOKEN_EXPIRED) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiErrorCode.TOKEN_EXPIRED, "Access token expired");
        } else if (code == ApiErrorCode.TOKEN_INVALID) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiErrorCode.TOKEN_INVALID, "Access token is not valid");
        } else {
            write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN,
                ApiErrorCode.FORBIDDEN, "Access denied");
    }

    private void write(HttpServletResponse response, int status, ApiErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(code, message));
    }
}
