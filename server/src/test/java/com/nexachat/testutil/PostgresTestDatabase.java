package com.nexachat.testutil;

import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

/** Owns a uniquely named PostgreSQL database for one integration-test scope. */
public final class PostgresTestDatabase implements AutoCloseable {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:5433/nexachat_test";
    private static final String DEFAULT_USER = "nexachat";
    private static final String DEFAULT_PASSWORD = "nexachat";

    private final String databaseName;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String maintenanceUrl;
    private boolean closed;

    private PostgresTestDatabase(String databaseName, String jdbcUrl, String username,
                                 String password, String maintenanceUrl) {
        this.databaseName = databaseName;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.maintenanceUrl = maintenanceUrl;
    }

    public static PostgresTestDatabase create(String purpose) {
        String baseUrl = env("IT_DB_URL", DEFAULT_JDBC_URL);
        String username = env("IT_DB_USER", DEFAULT_USER);
        String password = env("IT_DB_PASSWORD", DEFAULT_PASSWORD);
        JdbcCoordinates coordinates = JdbcCoordinates.from(baseUrl);
        String databaseName = databaseName(purpose);
        String jdbcUrl = coordinates.urlFor(databaseName);
        String maintenanceUrl = coordinates.urlFor("postgres");

        try (Connection connection = DriverManager.getConnection(maintenanceUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\" WITH TEMPLATE template0");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create an isolated PostgreSQL test database", exception);
        }

        return new PostgresTestDatabase(databaseName, jdbcUrl, username, password, maintenanceUrl);
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public DataSource dataSource() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriver(new org.postgresql.Driver());
        dataSource.setUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        try (Connection connection = DriverManager.getConnection(maintenanceUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS \"" + databaseName + "\" WITH (FORCE)");
            closed = true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not drop isolated PostgreSQL test database " + databaseName,
                    exception);
        }
    }

    private static String databaseName(String purpose) {
        String safePurpose = purpose == null ? "test" : purpose.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (safePurpose.isEmpty()) {
            safePurpose = "test";
        }
        if (safePurpose.length() > 28) {
            safePurpose = safePurpose.substring(0, 28);
        }
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return "nexachat_it_" + safePurpose + "_" + suffix;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private record JdbcCoordinates(String serverPrefix, String query) {

        private static JdbcCoordinates from(String jdbcUrl) {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
                throw new IllegalArgumentException("IT_DB_URL must be a PostgreSQL JDBC URL");
            }
            int queryStart = jdbcUrl.indexOf('?');
            String address = queryStart < 0 ? jdbcUrl : jdbcUrl.substring(0, queryStart);
            int databaseSeparator = address.lastIndexOf('/');
            int authorityStart = "jdbc:postgresql://".length();
            if (databaseSeparator <= authorityStart || databaseSeparator == address.length() - 1) {
                throw new IllegalArgumentException("IT_DB_URL must include a base database name");
            }
            String query = queryStart < 0 ? "" : jdbcUrl.substring(queryStart);
            return new JdbcCoordinates(address.substring(0, databaseSeparator + 1), query);
        }

        private String urlFor(String database) {
            return serverPrefix + database + query;
        }
    }
}
