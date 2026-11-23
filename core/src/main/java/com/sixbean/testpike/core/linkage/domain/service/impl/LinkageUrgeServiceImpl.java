package com.sixbean.testpike.core.linkage.domain.service.impl;

import com.sixbean.testpike.core.linkage.api.dto.LinkageUrgeCandidate;
import com.sixbean.testpike.core.linkage.api.dto.LinkageUrgeResult;
import com.sixbean.testpike.core.linkage.api.dto.UpstreamOwnership;
import com.sixbean.testpike.core.linkage.domain.component.LinkageUpstreamOwnership;
import com.sixbean.testpike.core.linkage.domain.service.LinkageUrgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Hourly urge scan over pending {@code tp_linkage_impact} rows .
 * <p>Posture notes (all single-source by design):
 * <ul>
 * <li>pending = status 1/2/3 AND {@code consumed_at IS NULL} (V515
 * semantics) — a consumed row is silent forever;</li>
 * <li>thresholds re-read from tp_sys_config every round (hot update);
 * 0 disables exactly that stage, invalid values fall back to the
 * factory default with a WARN — same readHours shape as Q4;</li>
 * <li>recipients: stage 1 = the upstream object's creator (the grouped
 * card's actor), stage 2 = {@code ProjectOwners} (role-2 members +
 * project creator), escalation skips the executor to avoid the Q4
 * self-noise pattern;</li>
 * <li>idempotency: calendar-day dedup keys
 * {@code linkage-urge:{impactId}:{day}} /
 * {@code linkage-escalate:{impactId}:{day}:{ownerId}} enforced through
 * the NotificationService dedup check before create (same accounting
 * as Q4, no second pipeline);</li>
 * <li>rows whose upstream object cannot be resolved (unknown kind, deleted
 * row) are skipped and reported as one aggregate WARN per round —
 * never a silent hole, never a per-row log storm.</li>
 * </ul>
 */
@Service
public class LinkageUrgeServiceImpl implements LinkageUrgeService {

    private static final Logger log = LoggerFactory.getLogger(LinkageUrgeServiceImpl.class);

    public static final String KEY_URGE = "linkage.urge-hours";
    public static final String KEY_ESCALATE = "linkage.escalate-hours";
    public static final int DEFAULT_URGE_HOURS = 24;
    public static final int DEFAULT_ESCALATE_HOURS = 168;
    /** Defensive per-round cap: the ledger is the queue, a backlog must not make one round unbounded. */
    static final int SCAN_LIMIT = 500;

    private final JdbcTemplate jdbc;
    private final com.sixbean.testpike.core.config.domain.service.ConfigService configService;
    private final com.sixbean.testpike.core.notification.domain.service.NotificationService notificationService;
    private final com.sixbean.testpike.core.common.domain.component.ProjectOwners projectOwners;
    private final LinkageUpstreamOwnership ownership;

    public LinkageUrgeServiceImpl(JdbcTemplate jdbc,
                                  com.sixbean.testpike.core.config.domain.service.ConfigService configService,
                                  com.sixbean.testpike.core.notification.domain.service.NotificationService notificationService,
                                  com.sixbean.testpike.core.common.domain.component.ProjectOwners projectOwners,
                                  LinkageUpstreamOwnership ownership) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.ownership = ownership;
    }

    @Override
    public LinkageUrgeResult scan() {
        int urgeHours = readHours(KEY_URGE, DEFAULT_URGE_HOURS);
        int escalateHours = readHours(KEY_ESCALATE, DEFAULT_ESCALATE_HOURS);
        int earliest = earliestEnabled(urgeHours, escalateHours);
        if (earliest < 0) {
            return new LinkageUrgeResult(0, 0, 0, 0);
        }
        LocalDateTime now = LocalDateTime.now();
        String day = LocalDate.now().toString();
        List<LinkageUrgeCandidate> rows = queryPending(now.minusHours(earliest));
        int urged = 0;
        int escalated = 0;
        int deduped = 0;
        int unresolvable = 0;
        for (LinkageUrgeCandidate c : rows) {
            long ageHours = Duration.between(c.annotatedAt(), now).toHours();
            boolean urgeStage = urgeHours > 0 && ageHours >= urgeHours;
            boolean escalateStage = escalateHours > 0 && ageHours >= escalateHours;
            if (!urgeStage && !escalateStage) {
                continue;
            }
            if (!ownership.knows(c.upstreamType())) {
                unresolvable++;
                continue;
            }
            UpstreamOwnership owner = ownership.resolve(c.upstreamType(), c.upstreamId()).orElse(null);
            if (owner == null || owner.createdBy() == null) {
                unresolvable++;
                continue;
            }
            String subject = describe(c, owner);
            if (urgeStage) {
                int[] r = deliver("linkage-urge:" + c.impactId() + ":" + day,
                        owner.createdBy(), owner.projectId(), "LINKAGE_URGE",
                        "联动影响待处置提醒",
                        subject + " 的变更影响下游 " + LinkageUpstreamOwnership.labelOf(c.downstreamType())
                                + " #" + c.downstreamId() + "，已等待 " + ageHours
                                + " 小时未处置，请到该对象页面的联动影响区一键处置。");
                urged += r[0];
                deduped += r[1];
            }
            if (escalateStage) {
                String executorName = displayName(owner.createdBy());
                for (Long ownerId : projectOwners.resolve(owner.projectId())) {
                    if (ownerId.equals(owner.createdBy())) {
                        continue;
                    }
                    int[] r = deliver("linkage-escalate:" + c.impactId() + ":" + day + ":" + ownerId,
                            ownerId, owner.projectId(), "LINKAGE_ESCALATE",
                            "联动影响超时升级",
                            subject + " 的下游变更影响已 " + ageHours
                                    + " 小时无人处置（执行人 " + executorName + "），请关注项目页面的联动影响区。");
                    escalated += r[0];
                    deduped += r[1];
                }
            }
        }
        if (unresolvable > 0) {
            log.warn("联动催办扫描：{} 条待处置影响的上游对象无法解析归属（类型未登记或对象行已删除），本轮跳过；修复位=联动边登记与对象账",
                    unresolvable);
        }
        return new LinkageUrgeResult(rows.size(), urged, escalated, deduped);
    }

    /** Positive minimum of the two stages; -1 when both stages are disabled (0 or negative). */
    static int earliestEnabled(int urgeHours, int escalateHours) {
        int earliest = -1;
        if (urgeHours > 0) {
            earliest = urgeHours;
        }
        if (escalateHours > 0) {
            earliest = earliest < 0 ? escalateHours : Math.min(earliest, escalateHours);
        }
        return earliest;
    }

    /** One statement; oldest first so a capped round always feeds the longest-waiting rows. */
    private List<LinkageUrgeCandidate> queryPending(LocalDateTime scanBefore) {
        return jdbc.query(
                "SELECT id, upstream_object_type, upstream_object_id,"
                + " downstream_object_type, downstream_id, annotated_at"
                + " FROM tp_linkage_impact"
                + " WHERE consumed_at IS NULL AND status IN (1, 2, 3)"
                + " AND annotated_at <= ?"
                + " ORDER BY annotated_at"
                + " LIMIT ?",
                (rs, i) -> new LinkageUrgeCandidate(rs.getLong("id"),
                        rs.getString("upstream_object_type"), rs.getLong("upstream_object_id"),
                        rs.getString("downstream_object_type"), rs.getLong("downstream_id"),
                        rs.getObject("annotated_at", LocalDateTime.class)),
                scanBefore, SCAN_LIMIT);
    }

    private String describe(LinkageUrgeCandidate c, UpstreamOwnership owner) {
        String kind = LinkageUpstreamOwnership.labelOf(c.upstreamType());
        return owner.title() == null || owner.title().isBlank()
                ? "「" + kind + "」#" + c.upstreamId()
                : "「" + kind + "」《" + owner.title() + "》";
    }

    /**
     * Send one notification, counting like Q4: [0,1] on a dedup-key hit,
     * [1,0] on a fresh send. The dedup semantics themselves live in
     * NotificationService (single source) — this only checks first so the
     * round counts stay honest.
     */
    private int[] deliver(String dedupKey, long userId, long projectId,
                          String eventType, String title, String content) {
        if (notificationService.existsByDedupKey(dedupKey)) {
            return new int[] {0, 1};
        }
        notificationService.create(new com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput(
                userId, projectId, eventType, title, content, dedupKey));
        return new int[] {1, 0};
    }

    private String displayName(long userId) {
        return jdbc.query(
                "SELECT COALESCE(display_name, username) FROM tp_user WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : ("用户" + userId), userId);
    }

    /** Threshold read: invalid value falls back with a WARN; 0 is a legal stage-off. */
    private int readHours(String key, int defaultValue) {
        String raw = configService.getValue(key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("联动催办阈值配置 {} 非法值「{}」，回退默认 {}", key, raw, defaultValue);
            return defaultValue;
        }
    }
}
