package com.nexachat.observability;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlywayHealthIndicatorTest {

    @Test
    void reportsUpWhenValidateSucceedsAndNoMigrationIsPending() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        MigrationInfo current = mock(MigrationInfo.class);
        when(current.getVersion()).thenReturn(MigrationVersion.fromVersion("7"));
        when(current.getDescription()).thenReturn("reaction events");
        when(info.current()).thenReturn(current);
        when(info.pending()).thenReturn(new MigrationInfo[0]);
        when(info.applied()).thenReturn(new MigrationInfo[] { current });
        when(flyway.info()).thenReturn(info);

        var health = new FlywayHealthIndicator(providerOf(flyway)).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("version", "7");
    }

    @Test
    void reportsDownWhenMigrationsArePending() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(info.current()).thenReturn(null);
        when(info.pending()).thenReturn(new MigrationInfo[] { mock(MigrationInfo.class) });
        when(flyway.info()).thenReturn(info);

        var health = new FlywayHealthIndicator(providerOf(flyway)).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void reportsDownWhenValidationFails() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(info.current()).thenReturn(null);
        when(info.pending()).thenReturn(new MigrationInfo[0]);
        when(flyway.info()).thenReturn(info);
        doThrow(new IllegalStateException("migration failed")).when(flyway).validate();

        var health = new FlywayHealthIndicator(providerOf(flyway)).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
        assertThat(health.getDetails()).containsEntry("error", "IllegalStateException");
    }

    @Test
    void reportsUpWhenFlywayIsNotConfigured() {
        var health = new FlywayHealthIndicator(providerOf(null)).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("status", "not configured");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Flyway> providerOf(Flyway flyway) {
        ObjectProvider<Flyway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(flyway);
        return provider;
    }
}
