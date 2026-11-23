package com.sixbean.testpike.core.batch.domain.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Batch runtime parameter change counting surface, serving as the data source for the user-facing prompt indicating that a runtime change was not adopted.
 * <p>Semantics: after an UPDATE on the source tables (tp_project_env / tp_db_connection / tp_data_pool_item), reverse lookup batches on this environment
 * that are in status 2 pending, 4 running, or 5 paused, and persist one row per batch to {@code tp_batch_runtime_change}
 * (kind=env|connection|pool); terminal-state batches are not counted because pinned-version semantics are meaningful only within the batch lifecycle.
 * <p>fail-safe: counting is an observability surface, not the main path; an environment with no running batches yields zero silent rows; any exception, including a foreign-key race from concurrent batch deletion,
 * should only be warned and must not break the save main flow (counterexample: observability-surface failures must not escalate into business-surface failures).
 * <p>Under snapshot pinning, the changed values themselves are not adopted (claim reads the snapshot); this table only answers how many times changes were made and whether users are aware.
 */
@Component
public class BatchRuntimeChangeRecorder {

    private static final Logger log = LoggerFactory.getLogger(BatchRuntimeChangeRecorder.class);

 /** Change category (value domain of tp_batch_runtime_change.kind, referenced literally in the family). */
    public static final String KIND_ENV = "env";
    public static final String KIND_CONNECTION = "connection";
    public static final String KIND_POOL = "pool";

    private final JdbcTemplate jdbcTemplate;

    public BatchRuntimeChangeRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Environment parameter changes: count one row for each batch in status 2, 4, or 5 on this environment (multiple batches sharing an environment means each batch is affected by the change). */
    public void recordEnvChange(long envId, String kind) {
        try {
            int rows = jdbcTemplate.update("""
                    INSERT INTO tp_batch_runtime_change (batch_id, kind, changed_at)
                    SELECT b.id, ?, now() FROM tp_exec_batch b
                    WHERE b.env_id = ? AND b.status IN (2, 4, 5)
                    """, kind, envId);
            if (rows > 0) {
                log.debug("批次运行期变更计数 kind={} envId={} 批次数={}", kind, envId, rows);
            }
        } catch (RuntimeException e) {
            // Observability-surface fail-safe: counting failures must not break the source-table save main flow (race windows such as an already deleted environment or a concurrent terminal state).
            log.warn("批次运行期变更计数失败（不影响保存）kind={} envId={}: {}", kind, envId, e.getMessage());
        }
    }
}
