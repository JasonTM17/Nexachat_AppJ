package com.nexachat.config;

import com.nexachat.attachment.S3StorageConfiguration;
import com.nexachat.attachment.S3StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class StorageProviderConfigurationTest {

    @Test
    void createsS3ClientWhenProviderSettingsArePresent() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "nexachat.storage.provider=s3",
                        "nexachat.storage.s3.endpoint=https://account.r2.cloudflarestorage.com",
                        "nexachat.storage.s3.region=auto",
                        "nexachat.storage.s3.bucket=nexachat-test",
                        "nexachat.storage.s3.access-key-id=test-access-key",
                        "nexachat.storage.s3.secret-access-key=test-secret-key")
                .withUserConfiguration(StorageProviderConfiguration.class, S3StorageConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("s3StorageClient");
                    assertThat(context.getBean(S3StorageProperties.class).toString())
                            .doesNotContain("test-secret-key");
                });
    }

    @Test
    void rejectsAnUnsupportedStorageProviderAtStartup() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("nexachat.storage.provider=cloud")
                .withUserConfiguration(StorageProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("NEXACHAT_STORAGE_PROVIDER must be either 'local' or 's3'");
                });
    }

    @Test
    void failsFastWithSettingNamesWhenS3ConfigurationIsIncomplete() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "nexachat.storage.provider=s3",
                        "nexachat.storage.s3.endpoint=",
                        "nexachat.storage.s3.bucket=",
                        "nexachat.storage.s3.access-key-id=",
                        "nexachat.storage.s3.secret-access-key=")
                .withUserConfiguration(StorageProviderConfiguration.class, S3StorageConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("NEXACHAT_STORAGE_S3_ENDPOINT")
                            .hasStackTraceContaining("NEXACHAT_STORAGE_S3_BUCKET")
                            .hasStackTraceContaining("NEXACHAT_STORAGE_S3_ACCESS_KEY_ID")
                            .hasStackTraceContaining("NEXACHAT_STORAGE_S3_SECRET_ACCESS_KEY");
                    assertThat(context.getStartupFailure().toString()).doesNotContain("test-secret");
                });
    }
}
