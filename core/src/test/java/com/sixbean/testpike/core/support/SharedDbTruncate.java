package com.sixbean.testpike.core.support;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;


public final class SharedDbTruncate {

    /** PostgreSQL lock_not_available — the lock_timeout signal observed on real connections. */
    private static final String SQLSTATE_LOCK_NOT_AVAILABLE = "55P03";

    /** Bounded retry budget shared by deadlock-victim and lock-wait-timeout forms. */
    private static final int MAX_ATTEMPTS = 3;

    private SharedDbTruncate() {
    }

    
    public static void executeAll(JdbcTemplate jdbcTemplate, String... sqls) {
        executeAll(jdbcTemplate, null, sqls);
    }

    /** Observability overload for the lock-wait specs: same behavior, plus attempt accounting. */
    public static void executeAll(JdbcTemplate jdbcTemplate, TruncateAttemptListener listener, String... sqls) {
        int lockTimeoutSeconds = SharedDbTruncateConfig.resolveLockTimeoutSeconds();
        runWithRetry(jdbcTemplate, listener,
                withinLockBound(lockTimeoutSeconds, jdbc -> {
                    for (String sql : sqls) {
                        jdbc.execute(sql);
                    }
                }));
    }

    /**
     * Sweep-channel twin of {@link #executeAll}: runs a fixture-project teardown
     * callback on the same pooled connection under the same session lock budget
     * and the same bounded retry. R37 conversion moved suites from blanket
     * TRUNCATE to row-scoped project sweeps; the retry semantics (deadlock
     * victim + lock-wait timeout) are kept intact — only the cleanup channel
     * changed, so a long-lived concurrent writer can never stall teardown
     * unboundedly here either.
     */
    public static void runSweepWithLockBound(JdbcTemplate jdbcTemplate, Consumer<JdbcTemplate> sweepAction) {
        int lockTimeoutSeconds = SharedDbTruncateConfig.resolveLockTimeoutSeconds();
        runWithRetry(jdbcTemplate, null, withinLockBound(lockTimeoutSeconds, sweepAction));
    }

    /** Shared retry loop: attempt accounting, bounded 3 tries, rethrow-on-exhaust. */
    private static void runWithRetry(JdbcTemplate jdbcTemplate, TruncateAttemptListener listener,
            ConnectionCallback<Void> work) {
        for (int attempt = 1; ; attempt++) {
            if (listener != null) {
                listener.onAttempt(attempt);
            }
            try {
                jdbcTemplate.execute(work);
                return;
            } catch (DataAccessException e) {
                if (!isRetryable(e) || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * The pooled connection must never leak the session variable: SET runs on the same
     * connection as the statements and RESET is guaranteed in finally. The lock-wait failure
     * propagates through Spring's translation untouched so the retry classification below
     * stays pinned to the RED-observed exception shape.
     */
    private static ConnectionCallback<Void> withinLockBound(int lockTimeoutSeconds,
            Consumer<JdbcTemplate> body) {
        return con -> {
            try (Statement st = con.createStatement()) {
                st.execute("SET lock_timeout = " + (lockTimeoutSeconds * 1000L));
            }
            try {
                // suppressClose=true: the borrow must never retire the pooled
                // connection between the sweep's statements.
                body.accept(new JdbcTemplate(
                        new SingleConnectionDataSource(con, true)));
            } finally {
                try (Statement reset = con.createStatement()) {
                    reset.execute("RESET lock_timeout");
                }
            }
            return null;
        };
    }

    /** Deadlock victim (translated type) or lock-wait timeout (55P03 anywhere in the cause chain). */
    private static boolean isRetryable(DataAccessException e) {
        if (e instanceof PessimisticLockingFailureException) {
            return true;
        }
        for (Throwable root = e; root != null; root = root.getCause()) {
            if (root instanceof SQLException se
                    && SQLSTATE_LOCK_NOT_AVAILABLE.equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
