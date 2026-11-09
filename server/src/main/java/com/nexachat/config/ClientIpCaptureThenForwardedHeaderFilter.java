package com.nexachat.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records the container peer address, then applies Spring's forwarded-header
 * rewriting so rate limiting can trust the captured socket peer.
 */
public class ClientIpCaptureThenForwardedHeaderFilter extends OncePerRequestFilter {

    private final ForwardedHeaderFilter forwardedHeaderFilter = new ForwardedHeaderFilter();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getAttribute(ClientIpResolver.SOCKET_REMOTE_ADDR_ATTRIBUTE) == null) {
            request.setAttribute(ClientIpResolver.SOCKET_REMOTE_ADDR_ATTRIBUTE, request.getRemoteAddr());
        }
        forwardedHeaderFilter.doFilter(request, response, filterChain);
    }
}
