package com.nexachat.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Rejects misspelled storage providers before application services are created. */
@Configuration(proxyBeanMethods = false)
public class StorageProviderConfiguration {

    @Bean
    static BeanFactoryPostProcessor validateStorageProvider(Environment environment) {
        String provider = environment.getProperty("nexachat.storage.provider", "local");
        if (!"local".equals(provider) && !"s3".equals(provider)) {
            throw new IllegalStateException(
                    "NEXACHAT_STORAGE_PROVIDER must be either 'local' or 's3'");
        }
        if ("s3".equals(provider)) {
            List<String> missing = new ArrayList<>();
            require(environment, "nexachat.storage.s3.endpoint", "NEXACHAT_STORAGE_S3_ENDPOINT", missing);
            require(environment, "nexachat.storage.s3.bucket", "NEXACHAT_STORAGE_S3_BUCKET", missing);
            require(environment, "nexachat.storage.s3.access-key-id",
                    "NEXACHAT_STORAGE_S3_ACCESS_KEY_ID", missing);
            require(environment, "nexachat.storage.s3.secret-access-key",
                    "NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY", missing);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("S3 storage selected but required settings are missing: "
                        + String.join(", ", missing));
            }
        }
        return beanFactory -> { };
    }

    private static void require(Environment environment, String propertyName,
                                String environmentName, List<String> missing) {
        if (!StringUtils.hasText(environment.getProperty(propertyName))) {
            missing.add(environmentName);
        }
    }
}
