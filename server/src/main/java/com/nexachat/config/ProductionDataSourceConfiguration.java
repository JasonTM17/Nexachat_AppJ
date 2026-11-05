package com.nexachat.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Fails before bean creation if production datasource credentials are absent. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDataSourceConfiguration {

    @Bean
    static BeanFactoryPostProcessor requireProductionDatasourceCredentials(Environment environment) {
        return beanFactory -> {
            requireResolved(environment, "spring.datasource.username");
            requireResolved(environment, "spring.datasource.password");
        };
    }

    private static void requireResolved(Environment environment, String propertyName) {
        String value = environment.getProperty(propertyName);
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Required production property is missing: " + propertyName);
        }
        try {
            environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Required production property is unresolved: " + propertyName,
                    exception);
        }
    }
}
