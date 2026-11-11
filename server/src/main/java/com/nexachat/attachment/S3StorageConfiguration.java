package com.nexachat.attachment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Creates the private, path-style client only when S3 storage is selected. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nexachat.storage", name = "provider", havingValue = "s3")
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfiguration {

    /** Bounded retries: an object-store stall must not hold a request thread indefinitely. */
    private static final int MAX_RETRIES = 2;

    @Bean(destroyMethod = "close")
    S3Client s3StorageClient(S3StorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(properties.endpointUri())
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.accessKeyId(), properties.secretAccessKey())))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.connectionTimeout())
                        .socketTimeout(properties.socketTimeout()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(properties.apiCallTimeout())
                        .apiCallAttemptTimeout(properties.apiCallAttemptTimeout())
                        .retryPolicy(RetryPolicy.builder().numRetries(MAX_RETRIES).build())
                        .build())
                .build();
    }
}
