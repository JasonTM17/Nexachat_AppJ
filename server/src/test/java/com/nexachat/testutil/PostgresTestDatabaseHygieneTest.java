package com.nexachat.testutil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves isolated databases created for integration tests are dropped even when
 * the holder is closed explicitly (normal {@code @AfterAll}) or via try-with-resources.
 */
class PostgresTestDatabaseHygieneTest {

    @Test
    void createAndCloseRemovesTheDatabaseFromPostgres() throws SQLException {
        int before = countDatabasesLike("nexachat_it_hygiene_%");
        try (PostgresTestDatabase database = PostgresTestDatabase.create("hygiene-proof")) {
            assertThat(databaseExists(database.databaseName())).isTrue();
        }
        int after = countDatabasesLike("nexachat_it_hygiene_%");
        assertThat(after).isEqualTo(before);
    }

    private static int countDatabasesLike(String pattern) throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM pg_database WHERE datname LIKE ?")) {
            statement.setString(1, pattern);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static boolean databaseExists(String name) throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Connection adminConnection() throws SQLException {
        String base = System.getenv("IT_DB_URL");
        if (base == null || base.isBlank()) {
            base = "jdbc:postgresql://localhost:5433/nexachat_test";
        }
        String user = env("IT_DB_USER", "nexachat");
        String password = env("IT_DB_PASSWORD", "nexachat");
        int slash = base.lastIndexOf('/');
        String maintenance = base.substring(0, slash + 1) + "postgres";
        return DriverManager.getConnection(maintenance, user, password);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
