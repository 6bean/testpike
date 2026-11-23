package com.sixbean.testpike.core.common.domain.component;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Project owner recipient resolution is extracted for REVIEW_TIMEOUT escalation and ENV_UNHEALTHY/ENV_RECOVERED.
 * Alerts are shared under the DRY principle: extract common components when reused at least twice.
 * <p>The owner is tp_user_project role=2 (system owner, possibly multiple, output stably in ascending id order).
 * If no role=2 row exists, fall back to the project creator (created_by, 01: the creator automatically becomes the owner to prevent missed sends caused by missing historical data).
 *
 */
@Component
public class ProjectOwners {

    private final JdbcTemplate jdbcTemplate;

    public ProjectOwners(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The project owner user ID uses role=2, with fallback to the creator when absent. */
    public List<Long> resolve(long projectId) {
        List<Long> owners = jdbcTemplate.queryForList(
                "SELECT user_id FROM tp_user_project WHERE project_id = ? AND role = 2 ORDER BY user_id",
                Long.class, projectId);
        if (!owners.isEmpty()) {
            return owners;
        }
        return jdbcTemplate.queryForList(
                "SELECT created_by FROM tp_project WHERE id = ? AND created_by IS NOT NULL",
                Long.class, projectId);
    }

    /**
 * The full recipient set is the union of the project owner and the project creator, extracted for DailyDigest and the following (b extraction).
 * COST_BUDGET_ALERT two existing implementations plus REPORT_DIGEST third-path reuse are incorporated under the DRY principle.
 * <p>LinkedHashSet ordered deduplication: when owner equals creator, keep only one entry (same approach as LoginLockService).
     */
    public LinkedHashSet<Long> resolveWithCreator(long projectId) {
        LinkedHashSet<Long> set = new LinkedHashSet<>(resolve(projectId));
        set.addAll(jdbcTemplate.queryForList(
                "SELECT created_by FROM tp_project WHERE id = ? AND created_by IS NOT NULL",
                Long.class, projectId));
        return set;
    }
}
