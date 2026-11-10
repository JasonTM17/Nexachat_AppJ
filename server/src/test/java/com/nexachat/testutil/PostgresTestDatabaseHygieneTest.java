package com.nexachat.testutil;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves isolated databases created for integration tests are dropped even when
 * the holder is closed explicitly (normal {@code @AfterAll}) or via try-with-resources.
 */
class PostgresTestDatabaseHygieneTest {

    @Test
    void createAndCloseRemovesTheDatabaseFromPostgres() {
        String databaseName;
        try (PostgresTestDatabase database = PostgresTestDatabase.create("hygiene-proof")) {
            databaseName = database.databaseName();
            assertThat(PostgresTestDatabase.existsOnServer(databaseName)).isTrue();
        }
        assertThat(PostgresTestDatabase.existsOnServer(databaseName)).isFalse();
    }

    /**
     * Regression for full-suite flakiness: an oracle that compared counts of every
     * {@code nexachat_it_hygiene_%} database failed when other workers dropped or leaked
     * a matching name between the before/after snapshots ({@code expected: 1 but was: 0}).
     */
    @Test
    void dropProofIsIndependentOfOtherHygieneDatabasesOnTheServer() {
        try (PostgresTestDatabase decoy = PostgresTestDatabase.create("hygiene-decoy")) {
            String databaseName;
            try (PostgresTestDatabase database = PostgresTestDatabase.create("hygiene-proof")) {
                databaseName = database.databaseName();
                assertThat(PostgresTestDatabase.existsOnServer(databaseName)).isTrue();
                assertThat(PostgresTestDatabase.existsOnServer(decoy.databaseName())).isTrue();
            }
            assertThat(PostgresTestDatabase.existsOnServer(databaseName)).isFalse();
            assertThat(PostgresTestDatabase.existsOnServer(decoy.databaseName())).isTrue();
        }
    }
}
