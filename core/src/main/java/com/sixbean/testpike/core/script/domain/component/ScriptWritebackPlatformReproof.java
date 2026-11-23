package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.cases.domain.component.AiReviewAutoApprovePolicy;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.TtlSecondsPolicy;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Platform re-validation of landed off-site write-backs — the owner of
 * evidence grade 3 ; the write-back service itself only ever
 * writes grades 1/2 and names this component as the grade-3 owner).
 * <p>Flow: an AFTER_COMMIT event of a write-back transaction schedules one
 * platform trial-run task through the single execution-task creation path
 * (test environments only — production never auto-executes, enforced again by
 * the creation guard itself), then registers the task id in a one-shot Redis
 * ledger. The ledger's recycle path is dual: it is explicitly deleted at the
 * settle point (both the pass and the fail branch) and TTL-expired when the
 * task never settles (R57: creation point paired with its own cleanup).
 * <p>When the executor relays a terminal result, {@link #onResultSubmitted}
 * consumes the ledger exactly once (GETDEL): pass raises the case to
 * evidence_grade=3 and files the reproof artifact on the audit trail the
 * review card reads; any other terminal verdict keeps the grade untouched
 * and records only the failure. Errors here are logged and never thrown —
 * reproof must not block or break the result relay chain, and the
 * write-back response never waits on it.
 * <p>Atomic consumption also gives the replay guard: submitResult itself
 * early-returns for already-terminal tasks, and a re-delivered settle finds
 * no ledger left, so one trial settles at most one reproof trail.
 */
@Component
public class ScriptWritebackPlatformReproof {

    /** Terminal grade: the platform itself re-ran the script and it passed. */
    static final int GRADE_PLATFORM_REPROOF = 3;
    /** Audit action shared by pass/fail/skip trails (review card evidence feed). */
    static final String ACTION_REPROOF = "script_platform_reproof";
    /** Hot-updatable ledger window key (tp_sys_config), read through the shared TTL policy. */
    static final String TTL_KEY = "script.writeback.reproof.ttl-seconds";
    /** Shipped default: a trial task may sit queued up to a day before the ledger lapses. */
    static final long DEFAULT_TTL_SECONDS = 86400;
    private static final String LEDGER_PREFIX = "script:reproof:";

    private static final Logger log = LoggerFactory.getLogger(ScriptWritebackPlatformReproof.class);

    private final ExecTaskService execTaskService;
    private final StringRedisTemplate redis;
    private final ConfigCacheHolder configCacheHolder;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbcTemplate;
    private final AiReviewAutoApprovePolicy autoApprovePolicy;

    public ScriptWritebackPlatformReproof(@Lazy ExecTaskService execTaskService,
                                          StringRedisTemplate redis,
                                          ConfigCacheHolder configCacheHolder, AuditLogService auditLogService,
                                          JdbcTemplate jdbcTemplate,
                                          AiReviewAutoApprovePolicy autoApprovePolicy) {
        this.execTaskService = execTaskService;
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
        this.auditLogService = auditLogService;
        this.jdbcTemplate = jdbcTemplate;
        this.autoApprovePolicy = autoApprovePolicy;
    }

    /** Public for cross-package fixtures . */
    public static String ledgerKey(long taskId) {
        return LEDGER_PREFIX + taskId;
    }

    long ttlSeconds() {
        return TtlSecondsPolicy.resolve(configCacheHolder.get(TTL_KEY), DEFAULT_TTL_SECONDS);
    }

    /**
     * Schedules the platform trial right after the write-back transaction is
     * durable. Runs on the committing thread; the few statements here (one env
     * lookup, one task insert, one Redis SET) are the accepted cost against
     * introducing a new async executor, and nothing here can unwind the
     * write-back — every failure is logged and, where a cause is namable,
     * recorded on the audit trail instead.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWritebackLanded(ScriptWritebackLanded landed) {
        try {
            scheduleReproofTask(landed);
        } catch (Exception e) {
            log.warn("platform reproof could not be scheduled for case {} "
                    + "(case keeps its written evidence grade): {}", landed.caseId(), e.getMessage());
        }
    }

    private void scheduleReproofTask(ScriptWritebackLanded landed) {
        List<Long> envIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_project_env WHERE project_id = ? AND env_type = 1"
                        + " ORDER BY id LIMIT 1",
                Long.class, landed.projectId());
        if (envIds.isEmpty()) {
            auditLogService.record(new AuditLogInput(landed.operatorId(), landed.projectId(),
                    "script", String.valueOf(landed.caseId()), ACTION_REPROOF, null,
                    Map.of("result", "skipped",
                            "reason", "项目没有可用的测试环境（生产环境不会自动执行），本次写回保持原证据分级")));
            return;
        }
        long taskId = execTaskService.create(new ExecTaskCreateInput(landed.projectId(),
                landed.caseId(), landed.schemaVersion(), landed.actionJson(), envIds.get(0), null));
        redis.opsForValue().set(ledgerKey(taskId),
                landed.operatorId() == null ? "" : String.valueOf(landed.operatorId()),
                Duration.ofSeconds(ttlSeconds()));
    }

    /**
     * Settle hook for the executor result relay (called from submitResult after
     * the terminal UPDATE, same placement discipline as the sibling write-back
     * hooks). Zero side effects for tasks this component never registered.
     */
    public void onResultSubmitted(ExecTaskInfo task, String result) {
        try {
            String raw = redis.opsForValue().getAndDelete(ledgerKey(task.id()));
            if (raw == null) {
                return;
            }
            Long operatorId = raw.isBlank() ? null : Long.valueOf(raw);
            if ("pass".equals(result)) {
                jdbcTemplate.update("UPDATE tp_test_case SET evidence_grade = ?, updated_at = now()"
                                + " WHERE id = ? AND evidence_grade IN (1, 2) AND deleted = 0",
                        GRADE_PLATFORM_REPROOF, task.caseId());
                auditLogService.record(new AuditLogInput(operatorId, task.projectId(),
                        "script", String.valueOf(task.caseId()), ACTION_REPROOF, null,
                        Map.of("taskId", task.id(), "result", "pass",
                                "evidenceGrade", GRADE_PLATFORM_REPROOF)));
                // hand-off: the optional quick-review channel reads this settle.
                // Disabled by factory default; when enabled it approves after this
                // transaction commits, so a failed approval can never roll back the
                // result relay (see AiReviewAutoApprovePolicy).
                autoApprovePolicy.tryAutoApproveAfterReproof(task.caseId(), operatorId);
            } else {
                auditLogService.record(new AuditLogInput(operatorId, task.projectId(),
                        "script", String.valueOf(task.caseId()), ACTION_REPROOF, null,
                        Map.of("taskId", task.id(), "result", result,
                                "reason", "平台试运行复证未通过，证据分级保持不变，用例继续在评审中等待人工判定")));
            }
        } catch (Exception e) {
            log.warn("platform reproof settle failed for task {} (relay chain unaffected): {}",
                    task.id(), e.getMessage());
        }
    }
}
