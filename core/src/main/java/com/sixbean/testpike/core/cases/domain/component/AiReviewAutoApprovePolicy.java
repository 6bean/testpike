package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

/**
 * Optional quick-review channel : when a platform re-proof raises a case to
 * the evidence floor and the operator has explicitly turned the channel on, the
 * case is approved through the single review path — {@link CaseService#review}
 * stays the only writer of review outcomes, so the review log, the version bump,
 * the snapshot and the review event all land exactly as they would for a human
 * pass. No second write path here by design.
 * <p>Eligibility (all must hold, read live per settle): the switch is on, the case
 * is pending review, its AI self-assessed confidence is high (1), its evidence
 * grade reaches the configured floor (shipped 3), and it carries no security-family
 * tag. The exemption cannot be lifted by the switch: security cases always wait for
 * a human, which is why the factory seed ships the channel off and any flip is an
 * explicit operator order.
 * <p>Threading: {@link #tryAutoApproveAfterReproof} may be called inside the result
 * relay transaction. Approval is therefore deferred to after commit (the grade raise
 * made in that transaction must be visible and a failed review must never poison
 * the relay commit); when no transaction is active it settles immediately. Every
 * failure is logged and swallowed — this channel is additive convenience, never a
 * blocker of the settle chain.
 */
@Component
public class AiReviewAutoApprovePolicy {

    /** Channel switch key (tp_sys_config). Ships '0'; hot-updatable, read live. */
    public static final String KEY_ENABLED = "ai.review.auto-approve.enabled";
    /** Evidence-grade floor key. Ships '3'; illegal shapes fall back to it. */
    public static final String KEY_MIN_GRADE = "ai.review.auto-approve.min-grade";
    /** Drift guard: must equal the V557 seed (a contract test pins both sides). */
    public static final int DEFAULT_MIN_GRADE = 3;
    /** tp_test_case.confidence: 1 = high AI self-assessment (2 medium, 3 low). */
    static final int CONFIDENCE_HIGH = 1;
    /**
     * Security family, per the tag ledger: any project tag whose name starts with
     * this prefix marks the case human-review-only regardless of the switch.
     */
    static final String SECURITY_TAG_PREFIX = "安全";
    /** Remark prefix pinned by the contract test (review-card trail wording). */
    static final String REMARK_PREFIX = "快速评审自动通过";

    private static final Logger log = LoggerFactory.getLogger(AiReviewAutoApprovePolicy.class);

    private final ConfigCacheHolder configCacheHolder;
    private final JdbcTemplate jdbcTemplate;
    private final CaseService caseService;

    public AiReviewAutoApprovePolicy(ConfigCacheHolder configCacheHolder,
                                     JdbcTemplate jdbcTemplate,
                                     @Lazy CaseService caseService) {
        this.configCacheHolder = configCacheHolder;
        this.jdbcTemplate = jdbcTemplate;
        this.caseService = caseService;
    }

    /**
     * Entry point from the grade-3 settle of a platform re-proof. Registers the
     * approval after the caller's commit (or runs it inline outside a transaction);
     * a disabled channel never registers anything at all.
     */
    public void tryAutoApproveAfterReproof(long caseId, Long operatorId) {
        if (!enabled()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new QuickReviewAutoApproveSync(this, caseId, operatorId));
        } else {
            settleQuietly(caseId, operatorId);
        }
    }

    boolean enabled() {
        String raw = configCacheHolder.get(KEY_ENABLED);
        return raw != null && ("1".equals(raw.trim()) || "true".equalsIgnoreCase(raw.trim()));
    }

    int minGrade() {
        String raw = configCacheHolder.get(KEY_MIN_GRADE);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MIN_GRADE;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("illegal {} value '{}' — falling back to the shipped default {}",
                    KEY_MIN_GRADE, raw, DEFAULT_MIN_GRADE);
            return DEFAULT_MIN_GRADE;
        }
    }

    /** Fail-soft approval attempt; never propagates toward the settle chain. */
    void settleQuietly(long caseId, Long operatorId) {
        try {
            settle(caseId, operatorId);
        } catch (Exception e) {
            log.warn("quick-review auto-approve skipped for case {} (case keeps waiting "
                    + "for human review): {}", caseId, e.getMessage());
        }
    }

    private void settle(long caseId, Long operatorId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT project_id, status, confidence, evidence_grade, version"
                        + " FROM tp_test_case WHERE id = ? AND deleted = 0", caseId);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, Object> row = rows.get(0);
        int status = ((Number) row.get("status")).intValue();
        if (status != CaseStatus.PENDING_REVIEW.code()) {
            return; // only a pending review can move; humans may already have judged it
        }
        Number confidence = (Number) row.get("confidence");
        if (confidence == null || confidence.intValue() != CONFIDENCE_HIGH) {
            return; // medium/low self-assessment always stays with a human reviewer
        }
        int grade = ((Number) row.get("evidence_grade")).intValue();
        int floor = minGrade();
        if (grade < floor) {
            return;
        }
        long projectId = ((Number) row.get("project_id")).longValue();
        if (isSecurityTagged(projectId, caseId)) {
            log.info("case {} carries a security-family tag — exempt from quick review,"
                    + " waiting for human review", caseId);
            return;
        }
        int version = ((Number) row.get("version")).intValue();
        caseService.review(operatorId, caseId, ReviewAction.PASS,
                REMARK_PREFIX + "：平台复证通过，证据分级 " + grade + " 达到下限 " + floor
                        + "，且 AI 自评为高置信", version);
    }

    private boolean isSecurityTagged(long projectId, long caseId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_tag ct JOIN tp_tag_dict td ON ct.tag_id = td.id"
                        + " WHERE ct.case_id = ? AND td.project_id = ? AND td.name LIKE ?",
                Integer.class, caseId, projectId, SECURITY_TAG_PREFIX + "%");
        return n != null && n > 0;
    }
}
