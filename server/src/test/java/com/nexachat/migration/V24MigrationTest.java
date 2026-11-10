package com.nexachat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** V24 drops the unused idx_conv_inbox; prove Flyway applies cleanly on a populated schema. */
class V24MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v24DropsUnusedInboxIndexOnPopulatedDatabase() throws Exception {
        String database = "nexachat_mig_v24_" + System.nanoTime();
        createDatabase(database);
        try {
            DataSource dataSource = dataSource(database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertThat(indexExists(statement, "idx_conv_inbox")).isFalse();
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static boolean indexExists(Statement statement, String index) throws SQLException {
        ResultSet indexes = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = '"
                        + index + "'");
        return indexes.next();
    }

    private static DataSource dataSource(String database) {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriver(new org.postgresql.Driver());
        dataSource.setUrl(serverUrl() + database);
        dataSource.setUsername(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        return dataSource;
    }

    private static String serverUrl() {
        return JDBC_URL.substring(0, JDBC_URL.lastIndexOf('/') + 1);
    }

    private static void createDatabase(String database) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database);
            statement.executeUpdate("CREATE DATABASE " + database);
        }
    }

    private static void dropDatabase(String database) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE IF EXISTS " + database);
        }
    }

    private static Connection adminConnection() throws SQLException {
        java.sql.Driver driver = new org.postgresql.Driver();
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("user", DB_USER);
        properties.setProperty("password", DB_PASSWORD);
        return driver.connect(serverUrl() + "postgres", properties);
    }
}
