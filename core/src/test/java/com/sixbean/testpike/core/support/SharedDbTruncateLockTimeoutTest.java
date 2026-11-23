package com.sixbean.testpike.core.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * real connections only — zero mocks): a peer transaction holding the table lock must not
 * stall the TRUNCATE queue forever. The fixture is expected to set a session-level
 * {@code lock_timeout} (system property > {@code TEST_TRUNCATE_LOCK_TIMEOUT_SECONDS} env >
 * default), fail fast on the Postgres lock-timeout signal, retry within the existing bounded
 * budget, and always restore the pooled connection's session variable.
 *
 * <p>The probe table is fixture-owned scratch (created/dropped by this class) so the specs
 * never truncate factory baseline tables.</p>
 */
class SharedDbTruncateLockTimeoutTest {

    /** System-property key overriding the session lock wait bound, seconds. */
    static final String LOCK_TIMEOUT_PROPERTY = SharedDbTruncateConfig.LOCK_TIMEOUT_PROPERTY;

    private static final String PROBE_TABLE = "tp_truncate_lock_probe";

    private static String jdbcUrl;
    private static String jdbcUser;
    private static String jdbcPassword;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void setUp() throws SQLException {
        jdbcUrl = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:15432/testpike");
        jdbcUser = System.getenv().getOrDefault("DB_USERNAME", "testpike");
        jdbcPassword = System.getenv().getOrDefault("DB_PASSWORD", "");
        SimpleDriverDataSource dataSource =
                new SimpleDriverDataSource(new org.postgresql.Driver(), jdbcUrl, jdbcUser, jdbcPassword);
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
        jdbcTemplate.execute("CREATE TABLE " + PROBE_TABLE + " (id BIGINT PRIMARY KEY)");
        jdbcTemplate.update("INSERT INTO " + PROBE_TABLE + " (id) VALUES (1)");
    }

    @AfterAll
    static void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + PROBE_TABLE);
    }

    /** Opens a transaction that locks the probe table row and keeps it (holder of ACCESS EXCLUSIVE queue). */
    private static Connection openLockHolder() throws SQLException {
        Connection holder = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
        holder.setAutoCommit(false);
        try (Statement st = holder.createStatement()) {
            st.execute("SELECT id FROM " + PROBE_TABLE + " WHERE id = 1 FOR UPDATE");
        }
        return holder;
    }

    /**
     * Evidence the ruling demands (do not guess the translation shape from memory): run a
     * TRUNCATE that hits SQLSTATE 55P03 on a real pooled path through JdbcTemplate and pin
     * the exception Spring actually raises plus its root SQLSTATE, so the retry catch list
     * is written against what was observed.
     */
    @Test
    void pinTranslationShapeOfPostgresLockTimeoutOnRealConnection() throws SQLException {
        Connection holder = openLockHolder();
        try {
            DataAccessException thrown = assertThrows(DataAccessException.class,
                    () -> jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                        boolean truncated;
                        try (Statement st = con.createStatement()) {
                            st.execute("SET lock_timeout = 500");
                            try {
                                st.execute("TRUNCATE " + PROBE_TABLE);
                                truncated = true;
                            } finally {
                                try (Statement reset = con.createStatement()) {
                                    reset.execute("RESET lock_timeout");
                                }
                            }
                        }
                        if (truncated) {
                            throw new IllegalStateException("TRUNCATE unexpectedly succeeded while locked");
                        }
                        return null;
                    }));
            Throwable root = thrown;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("[55P03-translation-probe] springType=" + thrown.getClass().getName()
                    + " rootType=" + root.getClass().getName());
            assertInstanceOf(SQLException.class, root, "root cause must be the JDBC SQLException");
            assertTrue("55P03".equals(((SQLException) root).getSQLState()),
                    "root SQLSTATE must be 55P03 lock_not_available, was " + ((SQLException) root).getSQLState());
        } finally {
            holder.rollback();
            holder.close();
        }
    }

    /**
     * Scenario B (holder never releases): with a 1s session lock bound the bounded retry
     * budget (3 attempts + backoff, all ≈2s apart) must exhaust and rethrow within a short
     * window. Against the pre-change fixture this blocks in the lock queue instead, so the
     * preemptive deadline fails — the RED proof that no wait bound exists today.
     */
    @Test
    void exhaustedWaitBoundFailsFastInsteadOfBlockingForever() throws SQLException {
        System.setProperty(LOCK_TIMEOUT_PROPERTY, "1");
        Connection holder = openLockHolder();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(12),
                    () -> assertThrows(DataAccessException.class,
                            () -> SharedDbTruncate.executeAll(jdbcTemplate, "TRUNCATE " + PROBE_TABLE)),
                    "fixture must fail fast once the retry budget is exhausted, not wait in the lock queue");
        } finally {
            System.clearProperty(LOCK_TIMEOUT_PROPERTY);
            // Release last so a still-blocked statement from a preempted deadline completes.
            holder.rollback();
            holder.close();
        }
    }

    /**
     * Scenario A (holder releases mid-run): with a 1s session lock bound the first attempt
     * times out, the holder then lets go, and the bounded retry completes the truncate —
     * attempts strictly increase and the call returns without throwing.
     */
    @Test
    void retrySucceedsOnceTheHolderReleasesMidRun() throws SQLException, InterruptedException {
        System.setProperty(LOCK_TIMEOUT_PROPERTY, "1");
        Connection holder = openLockHolder();
        AtomicInteger attempts = new AtomicInteger();
        try {
            Thread releaser = new Thread(() -> {
                try {
                    // Release after the 1s bound has definitely expired (attempt 1 must fail
                    // on the lock timeout) but before the second attempt begins.
                    Thread.sleep(1300);
                    holder.rollback();
                    holder.close();
                } catch (Exception releaseFailure) {
                    throw new IllegalStateException(releaseFailure);
                }
            });
            releaser.setDaemon(true);
            releaser.start();
            assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> SharedDbTruncate.executeAll(jdbcTemplate, attempt -> attempts.incrementAndGet(),
                            "TRUNCATE " + PROBE_TABLE));
            releaser.join(Duration.ofSeconds(5));
            assertTrue(attempts.get() >= 2,
                    "first attempt must have hit the lock bound before the release, attempts=" + attempts.get());
            assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM " + PROBE_TABLE, Integer.class),
                    "the retried truncate must have actually cleared the probe table");
        } finally {
            System.clearProperty(LOCK_TIMEOUT_PROPERTY);
            try {
                if (!holder.isClosed()) {
                    holder.rollback();
                    holder.close();
                }
            } catch (SQLException alreadyClosed) {
                // The releaser thread already closed it — nothing to clean up.
            }
        }
    }

    /**
     * Session-variable hygiene: after any fixture run the pooled connection must report the
     * default lock_timeout again (RESET in finally) — a leaked bound would silently change
     * semantics for every later test borrowing the same pooled connection.
     */
    @Test
    void lockTimeoutNeverLeaksOntoThePooledConnection() {
        System.setProperty(LOCK_TIMEOUT_PROPERTY, "2");
        try {
            SharedDbTruncate.executeAll(jdbcTemplate, "TRUNCATE " + PROBE_TABLE);
            Integer sessionBound = jdbcTemplate.queryForObject("SHOW lock_timeout", Integer.class);
            assertEquals(0, sessionBound, "lock_timeout must be reset to the server default after the fixture runs");
        } finally {
            System.clearProperty(LOCK_TIMEOUT_PROPERTY);
        }
    }
}
