package com.sixbean.testpike.core.cases.domain.service.impl;

import com.sixbean.testpike.core.cases.api.dto.ReviewRemindCandidate;
import com.sixbean.testpike.core.cases.api.dto.ReviewRemindResult;
import com.sixbean.testpike.core.cases.domain.service.ReviewRemindService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Review reminder scan implementation.
 * <p>Fetch all overdue pending items with a single SQL query (status 1/2, non-null assignee/assigned_at, and exceeding the earlier threshold of the active level).
 * Group the two levels in memory by timeout-hours to avoid duplicate similar SQL queries (DRY), and read the sys_config thresholds each round.
 * Changes take effect immediately, and 0 disables that level.
 * <p>Idempotency: notification dedup_key=review-remind:{caseId}:{reviewer}:{calendar day} /
 * For review-timeout:{caseId}:{calendar day}:{owner}, NotificationService.create directly reuses an existing key.
 * Reuse without resending by reusing tp_notification deduplication for zero new tables and enforcing reminder idempotency for the same case and reviewer.
 * Only one record is allowed per calendar day.
 * <p>Escalation recipient is the project owner (tp_user_project role=2, possibly multiple); when no role=2 rows exist, use the project creator as fallback.
 * The creator automatically becomes the owner via created_by to prevent missed notifications for historical data, and skip when the owner is also the reviewer.
 * Prevent self-escalation noise because the pending item is already covered by the reminder level.
 * <p>Preference filtering (three events in the preference matrix) depends on the preference feature; do not filter before it is delivered; REVIEW_TIMEOUT.
 * REVIEW_TIMEOUT is a mandatory subscription level (cannot be unsubscribed), and after the preference feature is implemented, preference filtering can be attached to the pipeline position before NotificationService creation.
 * This service does not need changes.
 *
 */
@Service
public class ReviewRemindServiceImpl implements ReviewRemindService {

    static final String KEY_REMIND_HOURS = "review.remind-hours";
    static final String KEY_TIMEOUT_HOURS = "review.timeout-hours";
    private static final int DEFAULT_REMIND_HOURS = 24;
    private static final int DEFAULT_TIMEOUT_HOURS = 72;
    /** Single-round scan limit (defensive: limit notification generation per round under extreme backlog, and remaining items continue in the next round because hourly tasks naturally roll). */
    private static final int SCAN_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(ReviewRemindServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;

    public ReviewRemindServiceImpl(JdbcTemplate jdbcTemplate, ConfigService configService,
                                   NotificationService notificationService, ProjectOwners projectOwners) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
    }

    @Override
    public ReviewRemindResult scan() {
        int remindHours = readHours(KEY_REMIND_HOURS, DEFAULT_REMIND_HOURS);
        int timeoutHours = readHours(KEY_TIMEOUT_HOURS, DEFAULT_TIMEOUT_HOURS);
        if (remindHours <= 0 && timeoutHours <= 0) {
            return new ReviewRemindResult(0, 0, 0, 0); // When both levels are disabled, short-circuit directly.
        }
        // The first-level threshold uses the earlier of the active levels (when remind is disabled, start from timeout to avoid missed scans).
        int effectiveHours = remindHours > 0 ? remindHours : timeoutHours;
        List<ReviewRemindCandidate> candidates = queryCandidates(
                LocalDateTime.now().minusHours(effectiveHours));

        LocalDateTime timeoutBefore = LocalDateTime.now().minusHours(timeoutHours);
        String today = LocalDate.now().toString();
        int reminded = 0;
        int escalated = 0;
        int deduped = 0;

        for (ReviewRemindCandidate c : candidates) {
 // Reminder level (reviewer): send when remind is enabled; continue daily reminders until processed even after escalation.
            if (remindHours > 0) {
                int[] r = deliver(remindKey(c, today), c.assigneeId(), c.projectId(),
                        "REVIEW_REMIND", "评审催办：" + c.caseNo(),
                        "用例「" + c.title() + "」（" + c.caseNo()
                                + "）分配给你已超 " + remindHours + " 小时未评审，请尽快处理。");
                reminded += r[0];
                deduped += r[1];
            }
            // Escalation level (project owner, mandatory subscription): trigger when timeout threshold is exceeded; must also scan when remind is disabled (already included in the preceding query).
            if (timeoutHours > 0 && !c.assignedAt().isAfter(timeoutBefore)) {
                int[] r = escalate(c, today, timeoutHours);
                escalated += r[0];
                deduped += r[1];
            }
        }
        return new ReviewRemindResult(candidates.size(), reminded, escalated, deduped);
    }

    /**
     * Timeout escalation: send one notification per owner (include owner in the key to prevent mutual suppression); skip when the owner is the reviewer.
     * Prevent self-escalation noise because the pending item is already covered by the reminder level; counting is consistent with the reminder level:
     * Count sent/dedup by notification record (multiple owners on the same day each have one record and are not deduplicated against each other).
     */
    private int[] escalate(ReviewRemindCandidate c, String today, int timeoutHours) {
        int sent = 0;
        int dedup = 0;
        String ownerNames = null; // Multiple owners share the content (assemble the first one and reuse it because the content is identical, DRY).
        for (Long ownerId : projectOwners.resolve(c.projectId())) {
            if (ownerId == c.assigneeId()) {
                continue;
            }
            if (ownerNames == null) {
                ownerNames = displayName(c.assigneeId());
            }
            int[] r = deliver(escalateKey(c, today, ownerId), ownerId, c.projectId(),
                    "REVIEW_TIMEOUT", "评审超时：" + c.caseNo(),
                    "用例「" + c.title() + "」（" + c.caseNo() + "）评审已超 " + timeoutHours
                            + " 小时未处理（评审人 " + ownerNames + "），请跟进。");
            sent += r[0];
            dedup += r[1];
        }
        return new int[] {sent, dedup};
    }

    /**
     * Send one notification: return [0,1] when an existing dedup_key is hit (deduplication skip), and return [1,0] for a new send.
     * Reuse the key deduplication semantics of NotificationService.create (do not implement duplicate logic); create is idempotent and returns the existing ID.
     * Therefore, check before sending (existsByDedupKey) to ensure accurate counting.
     */
    private int[] deliver(String dedupKey, long userId, long projectId,
                          String eventType, String title, String content) {
        if (notificationService.existsByDedupKey(dedupKey)) {
            return new int[] {0, 1};
        }
        notificationService.create(new NotificationCreateInput(
                userId, projectId, eventType, title, content, dedupKey));
        return new int[] {1, 0};
    }

    /** Pending candidate query (status 1/2, assigned, has assigned_at, exceeds scan line, assigned_at ascending means oldest first). */
    private List<ReviewRemindCandidate> queryCandidates(LocalDateTime scanBefore) {
        return jdbcTemplate.query("""
                SELECT tc.id, tc.case_no, tc.title, tc.project_id, tc.assignee_id, tc.assigned_at
                  FROM tp_test_case tc
                 WHERE tc.deleted = 0 AND tc.status IN (1, 2)
                   AND tc.assignee_id IS NOT NULL AND tc.assigned_at IS NOT NULL
                   AND tc.assigned_at <= ?
                 ORDER BY tc.assigned_at
                 LIMIT ?""",
                (rs, i) -> {
                    long assignee = rs.getLong("assignee_id");
                    return new ReviewRemindCandidate(rs.getLong("id"), rs.getString("case_no"),
                            rs.getString("title"), rs.getLong("project_id"),
                            assignee, rs.getObject("assigned_at", LocalDateTime.class));
                },
                scanBefore, SCAN_LIMIT);
    }

 /** Project owner resolution has been extracted into the common ProjectOwners component (DRY: shared with ENV_UNHEALTHY alerts). */

    private String displayName(long userId) {
        return jdbcTemplate.query(
                "SELECT COALESCE(display_name, username) FROM tp_user WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : ("用户" + userId), userId);
    }

    /** Threshold reading (invalid values use a fallback to defaults; 0 disables that level and is determined by the caller, not normalized here). */
    private int readHours(String key, int defaultValue) {
        String raw = configService.getValue(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("催办阈值配置 {} 非法值「{}」，回退默认 {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    /** Reminder deduplication key (calendar-day level: only one record per case, reviewer, and calendar day). */
    static String remindKey(ReviewRemindCandidate c, String day) {
        return "review-remind:" + c.caseId() + ":" + c.assigneeId() + ":" + day;
    }

    /** Escalation deduplication key (includes owner: multiple owners each have one record and do not suppress each other). */
    static String escalateKey(ReviewRemindCandidate c, String day, long ownerId) {
        return "review-timeout:" + c.caseId() + ":" + day + ":" + ownerId;
    }
}
