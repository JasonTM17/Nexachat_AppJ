package com.nexachat.testconfig;

import com.nexachat.attachment.AvatarReferenceCoordinator;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Supplies the production-only avatar coordinator dependency to unit-test application contexts. */
@Configuration(proxyBeanMethods = false)
@Profile("test")
public class TestAvatarReferenceConfiguration {

    @Bean
    public AvatarReferenceCoordinator avatarReferenceCoordinatorForTest() {
        return Mockito.mock(AvatarReferenceCoordinator.class);
    }
}
