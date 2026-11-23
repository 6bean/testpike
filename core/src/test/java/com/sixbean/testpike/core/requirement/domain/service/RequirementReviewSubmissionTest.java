package com.sixbean.testpike.core.requirement.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.api.dto.RequirementInfo;
import com.sixbean.testpike.core.requirement.api.dto.RequirementReviewAction;
import com.sixbean.testpike.core.requirement.api.dto.RequirementUpdateInput;
import com.sixbean.testpike.core.support.RequirementFixtureCleaner;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Landing tests for the explicit submit-review door of the requirement chain
 * (batched review: conversation accepts only roll versions, never reviews — one
 * deliberate submit writes the ledger and reopens the knowledge-base path).
 * Covers: PASS on a changed requirement flips it back to active, records the
 * ledger at the resulting version and a replay on the same version writes
 * nothing; edits after a PASS go stale (no auto-review) until re-submitted;
 * NEED_CHANGE/REJECT refuse without a remark; and a soft-deleted requirement
 * is invisible to the review door.
 * <p>Runs against the real dev database - no mocks. Fixtures self-insert rows
 * and clean them up by collected ids; factory tables are never TRUNCATED.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementReviewSubmissionTest {

    @Autowired
    private RequirementService requirementService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbc;

    private RequirementFixtureCleaner fixtures;
    private long project;

    private long createProject() {
        String code = "arb" + UUID.randomUUID().toString().substring(0, 8);
        long pid = projectService.create(1L,
                new ProjectCreateInput("攒批评审项目" + code, code, "zh-CN", "P1", null));
        fixtures.trackProject(pid);
        return pid;
    }

    private RequirementInfo createRequirement(String title) {
        RequirementInfo info = requirementService.create(1L,
                new RequirementCreateInput(project, 3, null, title, "验收：评审链真实落账", null));
        fixtures.trackRequirement(info.id());
        return info;
    }

    @BeforeEach
    void seedProject() {
        fixtures = new RequirementFixtureCleaner(jdbc);
        project = createProject();
    }

    @AfterEach
    void cleanup() {
        fixtures.cleanup();
    }

    private int ledgerRows(long requirementId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM tp_requirement_review_log WHERE requirement_id = ?",
                Integer.class, requirementId);
        return n == null ? 0 : n;
    }

    private Integer latestAppliedVersion(long requirementId) {
        List<Integer> rows = jdbc.query(
                "SELECT applied_version FROM tp_requirement_review_log"
                        + " WHERE requirement_id = ? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getInt(1), requirementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void passOnAChangedRequirementFlipsItActiveLedgersTheResultingVersionAndReplaysClean() {
        RequirementInfo created = createRequirement("登录失败五次锁定账号" + UUID.randomUUID());
        requirementService.update(1L, created.id(),
                new RequirementUpdateInput(null, null, null, null, null, 2, null));

        RequirementInfo reviewed = requirementService.submitReview(
                1L, created.id(), RequirementReviewAction.PASS, null);

        assertEquals(Integer.valueOf(1), reviewed.status(), "PASS returns a changed row to active");
        assertEquals(Integer.valueOf(3), reviewed.version(),
                "content untouched, status flip rides the same update chain (v1 create, v2 changed, v3 active)");
        assertEquals(Integer.valueOf(1), ledgerRows(created.id()));
        assertEquals(reviewed.version(), latestAppliedVersion(created.id()));

        RequirementInfo replay = requirementService.submitReview(
                1L, created.id(), RequirementReviewAction.PASS, null);

        assertEquals(reviewed.version(), replay.version(), "replay never bumps the version");
        assertEquals(Integer.valueOf(1), ledgerRows(created.id()),
                "same-version PASS replay is idempotent: no extra ledger row");
    }

    @Test
    void editsAfterAPassStayUnreviewedUntilTheNextExplicitSubmit() {
        RequirementInfo created = createRequirement("导出报表含脱敏列" + UUID.randomUUID());
        requirementService.submitReview(1L, created.id(), RequirementReviewAction.PASS, null);
        assertEquals(Integer.valueOf(1), latestAppliedVersion(created.id()));

        requirementService.update(1L, created.id(),
                new RequirementUpdateInput("导出报表含脱敏列与页脚水印", null, null, null, null, null, null));

        assertEquals(Integer.valueOf(1), ledgerRows(created.id()),
                "a later edit never writes review history by itself (freshness is derived:"
                        + " the ledger stops at v1 while the row lives at v2)");

        RequirementInfo resubmitted = requirementService.submitReview(
                1L, created.id(), RequirementReviewAction.PASS, null);
        assertEquals(Integer.valueOf(2), resubmitted.version());
        assertEquals(Integer.valueOf(2), latestAppliedVersion(created.id()),
                "explicit re-submit re-grants the review on the new version");
        assertEquals(Integer.valueOf(2), ledgerRows(created.id()));
    }

    @Test
    void needChangeAndRejectRefuseWithoutARemarkAndWriteNothing() {
        RequirementInfo created = createRequirement("批量导入限流至五百行" + UUID.randomUUID());

        assertThrows(ServiceException.class, () -> requirementService.submitReview(
                1L, created.id(), RequirementReviewAction.NEED_CHANGE, null));
        assertThrows(ServiceException.class, () -> requirementService.submitReview(
                1L, created.id(), RequirementReviewAction.REJECT, "  "));
        assertEquals(Integer.valueOf(0), ledgerRows(created.id()),
                "refused submissions leave zero ledger");
        assertEquals(Integer.valueOf(1), requirementService.get(created.id()).version(),
                "refused submissions leave zero version churn");
    }

    @Test
    void aSoftDeletedRequirementIsInvisibleToTheReviewDoor() {
        RequirementInfo created = createRequirement("支付回调幂等去重窗口" + UUID.randomUUID());
        requirementService.delete(1L, created.id());

        assertThrows(ServiceException.class, () -> requirementService.submitReview(
                1L, created.id(), RequirementReviewAction.PASS, null));
        assertEquals(Integer.valueOf(0), ledgerRows(created.id()));
    }
}
