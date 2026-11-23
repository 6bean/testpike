package com.sixbean.testpike.core.deadletter.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterReplayService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Auto-replay loop + dead-letter backlog alert for operators.
 * <p>What this component does: periodically reposts pending dead-letter rows whose
 * failure class is on the whitelist (PROVIDER_TRANSIENT / CONFIG_MISSING / LLM_TIMEOUT)
 * back to their original exchange, and alerts the system admins when the pending
 * backlog grows past the configured threshold. Everything actionable about a
 * replay/void (guards, idempotent-window semantics, replay_round accounting, the
 * ENTITY_GONE ban) lives in the replay service {@link DeadLetterReplayService} —
 * this loop is its second consumer and holds no replay logic of its own (single
 * egress).
 * <p>Upstream gate without a second probe: a whitelisted
 * class that produced a dead-letter row inside the interval window counts as
 * still-failing upstream evidence, so zero rows of that class are reposted this
 * round. The ledger itself is the probe signal — no parallel health-checker is
 * introduced. Rows that keep coming back are bounded by the round budget and
 * auto-voided (replay-storm bound), with an escalation notice per voided row.
 * <p>Cadence: the cron tick is fixed (property, per-minute default) and the real
 * interval is the K8 key re-read every round — at most one system action per
 * interval, measured against the freshest row the loop itself touched
 * ({@code secondsSinceLastSystemActivity}, three-state: null = never ran = open).
 * <p>Commercial tier note (O3-a-3): the auto-replay loop and the alert subscription
 * are enterprise-gated features in the open-source distribution snapshot (license
 * gate strips them); the development side implements them fully, the master switch
 * seeds to off so a fresh install never reposts anything until an operator turns it on.
 * <p>Per-round audit posture (same as void): the ledger row is the audit —
 * operator carries {@code system-replay-auto-replay}/{@code system-replay-auto-void},
 * replay_round counts the attempts, void_reason states why the loop gave up. Nothing
 * is deleted, nothing is silently skipped.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DeadLetterAutoReplayScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAutoReplayScheduler.class);

    /** Operator marker for auto reposts (row-level audit identity of the loop). */
    public static final String AUTO_REPLAY_OPERATOR = "system-replay-auto-replay";
    /** Operator marker for the N-round give-up voids. */
    public static final String AUTO_VOID_OPERATOR = "system-replay-auto-void";
    /** Dictionary event for the K2 backlog alert (NotificationEventDict). */
    public static final String BACKLOG_EVENT = "MQ_DLQ_BACKLOG";

    /** K8 keys . */
    public static final String ENABLED_KEY = "mq.dlq.auto-replay.enabled";
    public static final String INTERVAL_SECONDS_KEY = "mq.dlq.auto-replay.interval-seconds";
    public static final String BATCH_LIMIT_KEY = "mq.dlq.auto-replay.batch-limit";
    public static final String MAX_ROUNDS_KEY = "mq.dlq.auto-replay.max-rounds";
    public static final String BACKLOG_THRESHOLD_KEY = "mq.dlq.backlog-alert-threshold";

    static final String DEFAULT_ENABLED = "0";
    static final int DEFAULT_INTERVAL_SECONDS = 300;
    static final int DEFAULT_BATCH_LIMIT = 50;
    static final int DEFAULT_MAX_ROUNDS = 3;
    static final int DEFAULT_BACKLOG_THRESHOLD = 500;

    /** O3-a-1 whitelist; ENTITY_GONE/DESERIALIZATION_FAIL/UNKNOWN are never auto-reposted. */
    private static final Set<DeadLetterFailureClass> WHITELIST = EnumSet.of(
            DeadLetterFailureClass.PROVIDER_TRANSIENT,
            DeadLetterFailureClass.CONFIG_MISSING,
            DeadLetterFailureClass.LLM_TIMEOUT);

    private static final DateTimeFormatter HOUR_WINDOW = DateTimeFormatter.ofPattern("yyyyMMddHH");
    /** Shared marker prefix of both system operators (cadence + audit scoping). */
    private static final String SYSTEM_OPERATOR_PREFIX = "system-replay-auto-";

    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigService configService;
    private final JobRunner jobRunner;
    private final DeadLetterReplayService replayService;
    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;

    public DeadLetterAutoReplayScheduler(NamedParameterJdbcTemplate jdbc,
                                         ConfigService configService,
                                         JobRunner jobRunner,
                                         DeadLetterReplayService replayService,
                                         NotificationService notificationService,
                                         AdminAlertRecipients adminAlertRecipients) {
        this.jdbc = jdbc;
        this.configService = configService;
        this.jobRunner = jobRunner;
        this.replayService = replayService;
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    /** Periodic round: cadence-gated auto replay, then the backlog alert check. */
    @SchedulerLock(name = "dlqAutoReplay", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Scheduled(cron = "${testpike.deadletter.auto-replay-cron:0 * * * * *}")
    public void sweep() {
        try {
            int replayed = runAutoReplayRound();
            int alerted = checkBacklogAlert(readInt(BACKLOG_THRESHOLD_KEY, DEFAULT_BACKLOG_THRESHOLD));
            if (replayed > 0 || alerted > 0) {
                log.info("死信自动补投回路本轮完成：补投 {} 条、积压告警 {} 封", replayed, alerted);
            }
        } catch (Exception e) {
            log.warn("死信自动补投回路失败（下一轮自动续跑，补投幂等）: {}", e.getMessage());
            jobRunner.alert("dlqAutoReplay", e);
        }
    }

    /**
     * One cadence-gated round driven by freshly read K8 values (the cron wrapper
     * and the ops tests share the underlying {@link #runRound} entry — same
     * DocumentExtractStallSweeper posture: heartbeat/decision separation).
     */
    public int runAutoReplayRound() {
        DeadLetterAutoReplayPolicy policy = new DeadLetterAutoReplayPolicy(
                "1".equals(readString(ENABLED_KEY, DEFAULT_ENABLED).trim()),
                readInt(INTERVAL_SECONDS_KEY, DEFAULT_INTERVAL_SECONDS),
                readInt(BATCH_LIMIT_KEY, DEFAULT_BATCH_LIMIT),
                readInt(MAX_ROUNDS_KEY, DEFAULT_MAX_ROUNDS));
        if (!policy.enabled() || policy.intervalSeconds() <= 0) {
            return 0;
        }
        Long sinceLast = secondsSinceLastSystemActivity();
        if (sinceLast != null && sinceLast < policy.intervalSeconds()) {
            return 0; // cadence floor: the loop acted recently, this tick spins idle
        }
        return runRound(policy);
    }

    /**
 * Business round against an explicit policy (unit-testable entry):
 * whitelist class gate -> oldest-first selection inside the batch cap ->
 * replayOne through the primitives -> exhausted rows auto-voided.
 *
 * @return rows reposted this round
 */
    public int runRound(DeadLetterAutoReplayPolicy policy) {
        if (!policy.enabled()) {
            return 0;
        }
        int replayed = 0;
        for (DeadLetterFailureClass failureClass : WHITELIST) {
            int remaining = policy.batchLimit() - replayed;
            if (remaining <= 0) {
                break;
            }
            if (hasFreshArrivalWithin(failureClass, policy.intervalSeconds())) {
                continue; // upstream evidence still failing for this class: spin, no repost
            }
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM tp_mq_dead_letter WHERE status = :pending"
                            + " AND failure_class = :failureClass AND replay_round < :maxRounds"
                            + " AND created_at < now() - make_interval(secs => :interval)"
                            + " ORDER BY created_at ASC LIMIT :cap",
                    new MapSqlParameterSource("pending", DeadLetterLedgerStatus.PENDING.dbValue())
                            .addValue("failureClass", failureClass.name())
                            .addValue("maxRounds", policy.maxRounds())
                            .addValue("interval", policy.intervalSeconds())
                            .addValue("cap", remaining),
                    Long.class);
            for (Long id : ids) {
                try {
                    replayService.replayOne(id, true, AUTO_REPLAY_OPERATOR);
                    replayed++;
                } catch (RuntimeException ex) {
                    // Per-row guard trips (row already flipped, replay rejected downstream):
                    // skip and keep the batch moving — replayEach posture.
                    log.info("自动补投跳过行 {}: {}", id, ex.getMessage());
                }
            }
        }
        autoVoidExhausted(policy.maxRounds());
        return replayed;
    }

    /**
     * Rows that reached the round budget and landed back in the ledger again are
     * voided with the system operator, then escalated to the admins: after the
     * round budget is exhausted a row is auto-voided and an alert is raised.
     * voidLedger keeps reason+operator on the row.
     */
    private void autoVoidExhausted(int maxRounds) {
        List<Long> exhausted = jdbc.queryForList(
                "SELECT id FROM tp_mq_dead_letter WHERE status = :pending"
                        + " AND failure_class IN (:whitelist) AND replay_round >= :maxRounds"
                        + " ORDER BY created_at ASC",
                new MapSqlParameterSource("pending", DeadLetterLedgerStatus.PENDING.dbValue())
                        .addValue("whitelist", WHITELIST.stream().map(Enum::name).toList())
                        .addValue("maxRounds", maxRounds),
                Long.class);
        for (Long id : exhausted) {
            boolean flipped;
            try {
                flipped = replayService.voidLedger(id,
                        "自动补投已轮满 " + maxRounds + " 轮仍再次进入死信，系统自动作废；"
                                + "请人工核实上游故障原因后按需重新入库",
                        AUTO_VOID_OPERATOR);
            } catch (RuntimeException ex) {
                log.info("自动作废跳过行 {}: {}", id, ex.getMessage());
                continue;
            }
            if (flipped) {
                escalateAutoVoid(id, maxRounds);
            }
        }
    }

    private void escalateAutoVoid(long ledgerId, int maxRounds) {
        String hourWindow = LocalDateTime.now().format(HOUR_WINDOW);
        for (Long adminId : adminAlertRecipients.adminUserIds()) {
            try {
                notificationService.create(new NotificationCreateInput(adminId, null,
                        BACKLOG_EVENT, "死信自动补投轮次耗尽，已自动作废一条",
                        "死信账目 id=" + ledgerId + " 连续 " + maxRounds
                                + " 轮自动补投后仍回到死信队列，系统已自动作废并停止补投；"
                                + "请在系统管理-死信队列查看该记录的失败原因并决定后续处理。",
                        "MQ_DLQ_AUTO_VOID:" + ledgerId + ":" + hourWindow + ":" + adminId));
            } catch (Exception e) {
                log.warn("自动作废升级通知发送失败（ledger={}）: {}", ledgerId, e.getMessage());
            }
        }
    }

    /**
     * K2 check: strictly more pending rows than the threshold fans one
     * MQ_DLQ_BACKLOG letter out per admin per hour window (JOB_FAILED dedup
     * posture — key includes the recipient so the 2nd admin is never swallowed).
     *
     * @return notifications created
     */
    public int checkBacklogAlert(int threshold) {
        long pending = countPendingLedgerRows();
        if (threshold < 0 || pending <= threshold) {
            return 0;
        }
        String hourWindow = LocalDateTime.now().format(HOUR_WINDOW);
        int created = 0;
        for (Long adminId : adminAlertRecipients.adminUserIds()) {
            try {
                notificationService.create(new NotificationCreateInput(adminId, null,
                        BACKLOG_EVENT, "死信积压超过告警阈值",
                        "待处理死信共 " + pending + " 条，已超过告警阈值 " + threshold
                                + " 条；请在系统管理-死信队列按队列/失败类型查看积压"
                                + "（口径=账表 pending 数，非 RabbitMQ 深度）。",
                        "MQ_DLQ_BACKLOG:" + hourWindow + ":" + adminId));
                created++;
            } catch (Exception e) {
                log.warn("MQ_DLQ_BACKLOG 站内信发送失败（接收人id={}）: {}", adminId, e.getMessage());
            }
        }
        return created;
    }

    /** Pending rows of the whole ledger (the backlog figure the page must state honestly). */
    public long countPendingLedgerRows() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_mq_dead_letter WHERE status = :pending",
                new MapSqlParameterSource("pending", DeadLetterLedgerStatus.PENDING.dbValue()),
                Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Seconds since the freshest row the loop itself acted on, or null when no
     * such row exists. Three-state on purpose (P18): null means "never ran" and
     * must open the cadence gate, never fold into 0 (which would pin the gate shut).
     */
    public Long secondsSinceLastSystemActivity() {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - max(updated_at)))::bigint"
                        + " FROM tp_mq_dead_letter WHERE operator LIKE :prefix",
                new MapSqlParameterSource("prefix", SYSTEM_OPERATOR_PREFIX + "%"),
                Long.class);
    }

    /** Fresh dead-letter of this class inside the window = upstream still failing evidence. */
    private boolean hasFreshArrivalWithin(DeadLetterFailureClass failureClass, int intervalSeconds) {
        Long fresh = jdbc.queryForObject(
                "SELECT count(*) FROM tp_mq_dead_letter WHERE failure_class = :failureClass"
                        + " AND created_at > now() - make_interval(secs => :interval)",
                new MapSqlParameterSource("failureClass", failureClass.name())
                        .addValue("interval", intervalSeconds),
                Long.class);
        return fresh != null && fresh > 0;
    }

    private String readString(String key, String fallback) {
        String value = configService.getValue(key, fallback);
        return value == null ? fallback : value;
    }

    /** Fail-closed numeric parse: blank/garbage/non-positive falls back to the seeded default. */
    private int readInt(String key, int fallback) {
        try {
            int parsed = Integer.parseInt(readString(key, String.valueOf(fallback)).trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
