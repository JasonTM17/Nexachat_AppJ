package com.nexachat.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Replaces Boot's {@code ForwardedHeaderFilter} with a variant that captures the
 * socket peer first whenever {@code server.forward-headers-strategy=framework}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
class ClientIpFilterConfig {

    @Bean
    static BeanPostProcessor disableBootForwardedHeaderFilterRegistration() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if ("forwardedHeaderFilter".equals(beanName) && bean instanceof FilterRegistrationBean<?> registration) {
                    registration.setEnabled(false);
                }
                return bean;
            }
        };
    }

    @Bean
    FilterRegistrationBean<ClientIpCaptureThenForwardedHeaderFilter> clientIpCaptureThenForwardedHeaderFilter() {
        FilterRegistrationBean<ClientIpCaptureThenForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new ClientIpCaptureThenForwardedHeaderFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        return registration;
    }
}
