package com.nexachat.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class V26MigrationTest {

    private static final String JDBC_URL =
            env("IT_DB_URL", "jdbc:postgresql://localhost:5433/nexachat_test");
    private static final String DB_USER = env("IT_DB_USER", "nexachat");
    private static final String DB_PASSWORD = env("IT_DB_PASSWORD", "nexachat");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Test
    void v26AddsDeletedAtAndPartialPhoneUniqueIndex() throws Exception {
        String database = "nexachat_mig_v26_" + System.nanoTime();
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
                assertThat(columnExists(statement, "users", "deleted_at")).isTrue();
                assertThat(indexExists(statement, "users_phone_active_key")).isTrue();
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static boolean columnExists(Statement statement, String table, String column) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM information_schema.columns"
                        + " WHERE table_name = '" + table + "' AND column_name = '" + column + "'")) {
            return rs.next();
        }
    }

    private static boolean indexExists(Statement statement, String index) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT 1 FROM pg_indexes WHERE indexname = '" + index + "'")) {
            return rs.next();
        }
    }

    private static void createDatabase(String database) throws Exception {
        SimpleDriverDataSource admin = new SimpleDriverDataSource();
        admin.setDriverClass(org.postgresql.Driver.class);
        admin.setUrl(adminUrl());
        admin.setUsername(DB_USER);
        admin.setPassword(DB_PASSWORD);
        try (Connection connection = admin.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + database + "\"");
        }
    }

    private static void dropDatabase(String database) throws Exception {
        SimpleDriverDataSource admin = new SimpleDriverDataSource();
        admin.setDriverClass(org.postgresql.Driver.class);
        admin.setUrl(adminUrl());
        admin.setUsername(DB_USER);
        admin.setPassword(DB_PASSWORD);
        try (Connection connection = admin.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + database + "'");
            statement.execute("DROP DATABASE IF EXISTS \"" + database + "\"");
        }
    }

    private static String adminUrl() {
        int slash = JDBC_URL.lastIndexOf('/');
        return slash < 0 ? JDBC_URL : JDBC_URL.substring(0, slash) + "/postgres";
    }

    private static DataSource dataSource(String database) {
        String url = adminUrl().replace("/postgres", "/" + database);
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(org.postgresql.Driver.class);
        dataSource.setUrl(url);
        dataSource.setUsername(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        return dataSource;
    }
}
