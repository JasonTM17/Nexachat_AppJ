package com.nexachat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsTheClientIpHeaderAndTrustedProxies() {
        runner.withPropertyValues(
                        "nexachat.security.client-ip-header=CF-Connecting-IP",
                        "nexachat.security.trusted-proxy-cidrs=10.0.0.0/8")
                .run(context -> {
                    SecurityProperties properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.clientIpHeader()).isEqualTo("CF-Connecting-IP");
                    assertThat(properties.trustedProxyCidrs()).containsExactly("10.0.0.0/8");
                });
    }

    @Test
    void defaultsToForwardedForWhenTheHeaderIsUnset() {
        runner.run(context -> assertThat(context.getBean(SecurityProperties.class).clientIpHeader())
                .isEqualTo(ClientIpResolver.DEFAULT_CLIENT_IP_HEADER));
    }

    @Test
    void blankHeaderFallsBackToForwardedFor() {
        runner.withPropertyValues("nexachat.security.client-ip-header= ")
                .run(context -> assertThat(context.getBean(SecurityProperties.class).clientIpHeader())
                        .isEqualTo(ClientIpResolver.DEFAULT_CLIENT_IP_HEADER));
    }

    @Test
    void applicationYamlReadsTheHeaderFromItsEnvironmentVariable() throws IOException {
        PropertySource<?> application = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertThat(application.getProperty("nexachat.security.client-ip-header"))
                .isEqualTo("${NEXACHAT_CLIENT_IP_HEADER:X-Forwarded-For}");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class PropertiesConfiguration {
    }
}
