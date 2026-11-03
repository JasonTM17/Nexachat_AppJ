package com.nexachat;

import com.nexachat.auth.RefreshTokenRepository;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class NexaChatApplicationTests {

    // The "test" profile excludes JPA autoconfiguration (no live database), so the
    // repositories are not real beans — register mocks to satisfy service wiring.
    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void contextLoads() {
    }
}
