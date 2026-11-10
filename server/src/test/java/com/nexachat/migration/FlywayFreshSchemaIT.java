package com.nexachat.migration;

import com.nexachat.testutil.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway migrates an empty database through the shipped chain and validates. */
class FlywayFreshSchemaIT {

    @Test
    void migrateEmptyDatabaseThenValidate() {
        try (PostgresTestDatabase database = PostgresTestDatabase.create("flyway-fresh")) {
            Flyway flyway = Flyway.configure()
                    .dataSource(database.jdbcUrl(), database.username(), database.password())
                    .load();
            flyway.migrate();
            flyway.validate();

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            List<String> versions = jdbc.queryForList(
                    "SELECT version FROM flyway_schema_history ORDER BY installed_rank", String.class);
            assertThat(versions).isNotEmpty();
            assertThat(versions.getFirst()).matches("1|V1.*");
            int tableCount = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    """,
                    Integer.class);
            assertThat(tableCount).isGreaterThan(10);
        }
    }
}
