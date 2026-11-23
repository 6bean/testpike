package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.cases.domain.component.CaseStatus;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackEvidence;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackResult;
import com.sixbean.testpike.core.script.domain.component.ScriptWritebackLanded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Off-platform script write-back implementation.
 * <p>Flow: read the case (404 for missing/soft-deleted) → refuse an approved
 * asset before any write → land the new script version through the single
 * script save path (its optimistic-lock check is the anti-trample CAS) → pull
 * the case back to pending review with the evidence grade in one conditional
 * UPDATE (case row version bump invalidates stale reviewer drafts) → record
 * the write-back in the audit hash chain with the evidence envelope.
 * <p>Grade rule: evidence package present = 2 (off-platform proof on file),
 * absent = 1 (self-report). A newer write-back always regrades because the new
 * version was never platform-validated; grade 3 is owned by the trial-run
 * re-validation step, not by this service.
 */
@Service
public class ScriptWritebackServiceImpl implements ScriptWritebackService {

    /** Self-declared by the agent, nothing to verify. */
    static final int GRADE_SELF_REPORT = 1;
    /** Agent run evidence (digest envelope) is on file for the reviewer. */
    static final int GRADE_EVIDENCE_ON_FILE = 2;

    /**
     * Generated-by stamp for write-back scripts: the manual-authoring family
     * allowed by the current CHECK constraint. A dedicated off-site-agent value
     * lands together with the MCP tool catalog wiring (constraint widening
     * migration in the same batch); the write-back origin stays distinguishable
     * meanwhile via the script_writeback audit action.
     */
    private static final int GENERATED_BY_MANUAL_FAMILY = 4;

    private final CaseService caseService;
    private final ScriptService scriptService;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public ScriptWritebackServiceImpl(CaseService caseService, ScriptService scriptService,
                                      AuditLogService auditLogService, JdbcTemplate jdbcTemplate,
                                      ApplicationEventPublisher eventPublisher) {
        this.caseService = caseService;
        this.scriptService = scriptService;
        this.auditLogService = auditLogService;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public ScriptWritebackResult writeback(ScriptWritebackInput input) {
        CaseInfo current = caseService.get(input.caseId());
        if (current.status() == CaseStatus.APPROVED.code()) {
            throw new ServiceException(
                    "用例已评审通过（当前状态：已通过），站外写回不会覆盖已评审通过的资产；"
                            + "如需变更，请先在评审中退回该用例，或改走站内草案通道后重新写回",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int grade = input.evidence() == null ? GRADE_SELF_REPORT : GRADE_EVIDENCE_ON_FILE;

        ScriptInfo saved = scriptService.save(new ScriptSaveInput(
                input.caseId(), input.schemaVersion(), input.actionJson(),
                GENERATED_BY_MANUAL_FAMILY, input.operatorId(), input.expectedVersion()));

        int updated = jdbcTemplate.update("""
                UPDATE tp_test_case SET status = ?, evidence_grade = ?, version = version + 1,
                       updated_at = now()
                WHERE id = ? AND version = ? AND deleted = 0
                """, CaseStatus.PENDING_REVIEW.code(), grade, input.caseId(), current.version());
        if (updated == 0) {
            throw new OptimisticLockingException(
                    "用例已被他人修改（版本冲突），请刷新后重试（当前版本 " + current.version() + "）");
        }

        auditLogService.record(new AuditLogInput(input.operatorId(), current.projectId(),
                "script", String.valueOf(input.caseId()), "script_writeback",
                Map.of("caseStatus", current.status()),
                auditAfter(saved.version(), grade, input.evidence())));

        // hand the landed version to the platform re-validation
        // step. The listener runs AFTER_COMMIT, so the trial task is scheduled
        // only when this write-back is durable; grading to 3 belongs to that
        // step, never to this service.
        eventPublisher.publishEvent(new ScriptWritebackLanded(input.caseId(), current.projectId(),
                input.schemaVersion(), input.actionJson(), input.operatorId()));

        return new ScriptWritebackResult(input.caseId(), saved.version(),
                CaseStatus.PENDING_REVIEW.code(), grade);
    }

    /** Evidence digest envelope for the audit row; null-safe, no full log text. */
    private Map<String, Object> auditAfter(int scriptVersion, int grade, ScriptWritebackEvidence evidence) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("scriptVersion", scriptVersion);
        after.put("caseStatus", CaseStatus.PENDING_REVIEW.code());
        after.put("evidenceGrade", grade);
        if (evidence != null) {
            if (evidence.ranAt() != null) {
                after.put("ranAt", evidence.ranAt());
            }
            if (evidence.envFingerprint() != null) {
                after.put("envFingerprint", evidence.envFingerprint());
            }
            if (evidence.passed() != null) {
                after.put("passed", evidence.passed());
            }
            if (evidence.failed() != null) {
                after.put("failed", evidence.failed());
            }
            if (evidence.logSha256() != null) {
                after.put("logSha256", evidence.logSha256());
            }
        }
        return after;
    }
}
