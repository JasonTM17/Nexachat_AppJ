package com.nexachat.config;

import com.nexachat.auth.AuthSecurityErrorHandler;
import com.nexachat.auth.JwtAuthFilter;
import com.nexachat.auth.RateLimitService;
import com.nexachat.common.dto.JwtPrincipal;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ActuatorMetricsProbeController.class)
@Import(SecurityConfig.class)
class ActuatorAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private RagStatsAuthorizationManager adminAuthorizationManager;
    @MockBean
    private JwtAuthFilter jwtAuthFilter;
    @MockBean
    private SecurityHeadersFilter securityHeadersFilter;
    @MockBean
    private AuthSecurityErrorHandler securityErrorHandler;
    @MockBean
    private RateLimitService rateLimitService;
    @MockBean
    private ClientIpResolver clientIpResolver;

    @BeforeEach
    void configureTestDoubles() throws Exception {
        passThrough(jwtAuthFilter);
        passThrough(securityHeadersFilter);
        doAnswer(invocation -> {
            ((HttpServletResponse) invocation.getArgument(1)).setStatus(HttpServletResponse.SC_FORBIDDEN);
            return null;
        }).when(securityErrorHandler).handle(any(), any(), any());
    }

    @Test
    void actuatorMetricsUsesTheAdminPhoneAuthorizationManager() throws Exception {
        when(adminAuthorizationManager.check(any(), any())).thenReturn(new AuthorizationDecision(false));
        Authentication user = authenticationFor(new UUID(1L, 1L));

        mockMvc.perform(get("/actuator/metrics").with(authentication(user)))
                .andExpect(status().isForbidden());

        verify(adminAuthorizationManager).check(any(), any());
    }

    @Test
    void actuatorMetricsAllowsTheConfiguredAdminDecision() throws Exception {
        when(adminAuthorizationManager.check(any(), any())).thenReturn(new AuthorizationDecision(true));

        mockMvc.perform(get("/actuator/metrics").with(authentication(authenticationFor(new UUID(2L, 2L)))))
                .andExpect(status().isOk());

        verify(adminAuthorizationManager).check(any(), any());
    }

    private static Authentication authenticationFor(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new JwtPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES);
    }

    private static void passThrough(Filter filter) throws Exception {
        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(filter).doFilter(any(), any(), any());
    }
}
