package com.nexachat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.dto.ErrorResponse;
import com.nexachat.common.error.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects request bodies larger than
 * {@code nexachat.security.max-json-body-bytes} (default 1 MiB) before the
 * dispatcher parses them.
 *
 * <p>Multipart uploads are excluded: they carry their own 25 MB ceiling in
 * {@code spring.servlet.multipart} and are validated again in the storage
 * layer. The check is deliberately on {@code Content-Length} only — a body of
 * known length is the overwhelming case for the mobile clients, and a
 * chunked/streamed body is bounded at the reverse proxy
 * ({@code client_max_body_size}), which terminates every public deployment
 * in front of this service.
 *
 * <p>Runs ahead of the security chain so an oversized payload never reaches
 * authentication, authorization or a message converter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    static final long DEFAULT_MAX_BODY_BYTES = 1024L * 1024L;
    private static final String MULTIPART_PREFIX = "multipart/";

    private final ObjectMapper objectMapper;
    private final long maxBodyBytes;

    public RequestBodySizeLimitFilter(
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${nexachat.security.max-json-body-bytes:1048576}") long maxBodyBytes) {
        this.objectMapper = objectMapper;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith(MULTIPART_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > maxBodyBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
                    ApiErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds the maximum allowed size"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
