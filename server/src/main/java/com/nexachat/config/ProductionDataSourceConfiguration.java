package com.nexachat.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Fails before bean creation if required production coordinates are absent. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionDataSourceConfiguration {

    @Bean
    static BeanFactoryPostProcessor requireProductionDatasourceCredentials(Environment environment) {
        return beanFactory -> {
            requireResolved(environment, "spring.datasource.url");
            requireResolved(environment, "spring.datasource.username");
            requireResolved(environment, "spring.datasource.password");
            String storageProvider = environment.getProperty("nexachat.storage.provider", "local");
            if ("local".equals(storageProvider)) {
                if (!StringUtils.hasText(environment.getProperty("STORAGE_ROOT"))) {
                    throw new IllegalStateException("Required production property is missing: STORAGE_ROOT");
                }
                requireResolved(environment, "nexachat.storage.root");
            } else if (!"s3".equals(storageProvider)) {
                throw new IllegalStateException(
                        "NEXACHAT_STORAGE_PROVIDER must be either 'local' or 's3'");
            }
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
