package com.sixbean.testpike.core.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared cleanup ledger for notification-domain test fixtures (R40 single source —
 * the interactive-card, webhook-channel, webhook, service and preference suites all
 * consume this one teardown). Collects the users, notification ids, dedup-key
 * prefixes and execution projects a suite creates, then replays one deterministic
 * sequence: purge notifications matching tracked dedup prefixes, delete tracked
 * notification rows by id, prune every row hanging off tracked users (inbox rows,
 * subscription preferences, role grants, the user row itself) and drop tracked
 * projects together with their batches and tasks. Audit rows are never deleted:
 * tp_audit_log is append-only (DB trigger tp_audit_log_no_modify — TRUNCATE bypassed
 * row triggers, plain DELETE does not) and its pruning belongs to the retention
 * cleanup path only; suite audit rows ride the hash chain as legitimate appends
 * under unique per-run operators. Nothing else is truncated (R37) — factory rows
 * and concurrent suites' rows stay untouched.
 */
public class NotificationFixtureCleaner {

    private final JdbcTemplate jdbc;
    private final Set<String> dedupPrefixes = new LinkedHashSet<>();
    private final List<Long> notificationIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();

    public NotificationFixtureCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Keep one ledger instance per suite across the BeforeEach/AfterEach pair. */
    public static NotificationFixtureCleaner forTestRun(NotificationFixtureCleaner current,
                                                        JdbcTemplate jdbc) {
        return current == null ? new NotificationFixtureCleaner(jdbc) : current;
    }

    /** Create and track a suite-local user row (unique username per call site). */
    public long insertUser(String username, String displayName, int role) {
        Long id = jdbc.queryForObject("INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                + "VALUES (?, '$2a$10$x', ?, ?, 1) RETURNING id", Long.class, username, displayName, role);
        assert id != null;
        userIds.add(id);
        return id;
    }

    public void trackNotification(long id) {
        notificationIds.add(id);
    }

    public void trackDedupPrefix(String prefix) {
        dedupPrefixes.add(prefix);
    }

    public void trackProject(long id) {
        projectIds.add(id);
    }

    /** Combined teardown: purge tracked rows strictly by lineage, then reset the ledger. */
    public void cleanup() {
        for (String prefix : dedupPrefixes) {
            jdbc.update("DELETE FROM tp_notification WHERE dedup_key LIKE ?", prefix + "%");
        }
        dedupPrefixes.clear();
        for (Long id : notificationIds) {
            jdbc.update("DELETE FROM tp_notification WHERE id = ?", id);
        }
        notificationIds.clear();
        for (Long uid : userIds) {
            jdbc.update("DELETE FROM tp_notification WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM tp_notification_pref WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM tp_user_role WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM tp_user WHERE id = ?", uid);
        }
        userIds.clear();
        for (Long pid : projectIds) {
            jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", pid);
            jdbc.update("DELETE FROM tp_exec_batch WHERE project_id = ?", pid);
            jdbc.update("DELETE FROM tp_project WHERE id = ?", pid);
        }
        projectIds.clear();
    }
}
