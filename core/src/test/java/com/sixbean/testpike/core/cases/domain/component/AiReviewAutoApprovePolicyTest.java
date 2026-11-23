package com.sixbean.testpike.core.cases.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import com.sixbean.testpike.core.script.domain.component.ScriptWritebackPlatformReproof;
import com.sixbean.testpike.core.script.domain.service.ScriptWritebackService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.ScriptWritebackDrafts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quick-review auto-approve policy : factory-off contract, eligibility
 * guards and the security-tag exemption, on real DB and real Redis, no mocks.
 * <p>Contract pinned here:
 * (1) both keys ship off-the-shelf at enabled=0 / min-grade=3, identically in the
 * migration seed and the regenerated schema-init artifact;
 * (2) while the switch is off nothing moves: pending cases keep waiting for humans;
 * (3) when on, an eligible case (pending review, confidence high, grade at the
 * floor) is approved through the single review path — review log, version bump and
 * approval status all appear exactly as a human pass would leave them;
 * (4) every failing input stays with humans (grade below floor, confidence not
 * high, status not pending review);
 * (5) the security-tag exemption holds against a live switch (exemption-hit case,
 * R43 discipline: an exclusion branch without a proving fixture is a silent no-op);
 * (6) the grade floor is hot-configurable and illegal shapes fall back to the
 * shipped default;
 * (7) wiring: the grade-3 settle of a platform re-proof drives the whole channel
 * end to end when enabled.
 */
@SpringBootTest(classes = CoreApplication.class)
class AiReviewAutoApprovePolicyTest {

    @Autowired
    private AiReviewAutoApprovePolicy policy;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ScriptWritebackService scriptWritebackService;
    @Autowired
    private ScriptWritebackPlatformReproof platformReproof;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private final List<Long> trackedProjects = new CopyOnWriteArrayList<>();
    private final List<Long> trackedTasks = new CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (Long taskId : trackedTasks) {
            redis.delete(ScriptWritebackPlatformReproof.ledgerKey(taskId));
        }
        trackedTasks.clear();
        configCacheHolder.remove(AiReviewAutoApprovePolicy.KEY_ENABLED);
        configCacheHolder.remove(AiReviewAutoApprovePolicy.KEY_MIN_GRADE);
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "quick-review project " + code, code);
    }

    /** Seeds a case in an arbitrary review posture (fixture rows, not a product path). */
    private long pendingCase(long projectId, Integer confidence, int grade, int status) {
        long caseId = ScriptWritebackDrafts.createCase(caseService, projectId);
        jdbcTemplate.update("UPDATE tp_test_case SET confidence = ?, evidence_grade = ?,"
                + " status = ? WHERE id = ?", confidence, grade, status, caseId);
        return caseId;
    }

    private void bindTag(long projectId, long caseId, String tagName) {
        Long tagId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, ?, 4) RETURNING id",
                Long.class, projectId, tagName);
        jdbcTemplate.update("INSERT INTO tp_case_tag (case_id, tag_id) VALUES (?, ?)",
                caseId, tagId);
    }

    private int status(long caseId) {
        Integer s = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        return s == null ? -1 : s;
    }

    private int version(long caseId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_test_case WHERE id = ?", Integer.class, caseId);
        return v == null ? -1 : v;
    }

    private int autoPassReviewLogs(long caseId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_review_log WHERE case_id = ? AND action = ?"
                        + " AND remark LIKE '快速评审自动通过%'",
                Integer.class, caseId, "通过");
        return n == null ? 0 : n;
    }

    private void switchOn() {
        configCacheHolder.put(AiReviewAutoApprovePolicy.KEY_ENABLED, "1");
    }

    @Test
    void factorySeedsShipOffWithGradeThreeFloorInBothArtifacts() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("db/migration"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "repository root with db/migration must be reachable");
        String seed = Files.readString(root.resolve("db/migration/V557__ai_review_auto_approve_keys.sql"));
        String artifact = Files.readString(root.resolve("db/schema-init.sql"));
        for (String carrier : List.of(seed, artifact)) {
            assertTrue(carrier.contains("('ai.review.auto-approve.enabled', '0'"),
                    "the auto-approve channel must ship OFF (human gate relaxation is opt-in only)");
            assertTrue(carrier.contains("('ai.review.auto-approve.min-grade', '3'"),
                    "the evidence floor ships at 3 = platform re-proof passed");
        }
        assertEquals(3, AiReviewAutoApprovePolicy.DEFAULT_MIN_GRADE,
                "the code default must not drift from the seed (both name the shipped floor)");
    }

    @Test
    void disabledChannelLeavesEligibleCasesWithHumans() {
        long projectId = createProject("qa-off");
        long caseId = pendingCase(projectId, 1, 3, CaseStatus.PENDING_REVIEW.code());
        int before = version(caseId);

        policy.tryAutoApproveAfterReproof(caseId, 1L);

        assertEquals(CaseStatus.PENDING_REVIEW.code(), status(caseId),
                "no switch, no auto-approval: pending review keeps waiting for humans");
        assertEquals(before, version(caseId));
        assertEquals(0, autoPassReviewLogs(caseId));
    }

    @Test
    void eligibleCaseAutoApprovesThroughTheSingleReviewPath() {
        long projectId = createProject("qa-on");
        long caseId = pendingCase(projectId, 1, 3, CaseStatus.PENDING_REVIEW.code());
        int before = version(caseId);
        switchOn();

        policy.tryAutoApproveAfterReproof(caseId, 1L);

        assertEquals(CaseStatus.APPROVED.code(), status(caseId),
                "eligible case is approved, and only via the review path");
        assertEquals(before + 1, version(caseId), "optimistic version advanced exactly once");
        assertEquals(1, autoPassReviewLogs(caseId),
                "a review log row carries the auto-pass remark for the reviewer trail");
    }

    @Test
    void belowFloorInputsStayWithHumans() {
        long projectId = createProject("qa-floor");
        long lowGrade = pendingCase(projectId, 1, 2, CaseStatus.PENDING_REVIEW.code());
        long midConfidence = pendingCase(projectId, 2, 3, CaseStatus.PENDING_REVIEW.code());
        long notPending = pendingCase(projectId, 1, 3, CaseStatus.APPROVED.code());
        switchOn();

        policy.tryAutoApproveAfterReproof(lowGrade, 1L);
        policy.tryAutoApproveAfterReproof(midConfidence, 1L);
        policy.tryAutoApproveAfterReproof(notPending, 1L);

        assertEquals(CaseStatus.PENDING_REVIEW.code(), status(lowGrade),
                "grade below the floor never auto-approves");
        assertEquals(CaseStatus.PENDING_REVIEW.code(), status(midConfidence),
                "confidence other than high never auto-approves");
        assertEquals(CaseStatus.APPROVED.code(), status(notPending),
                "a case not pending review is left untouched (no double transition)");
    }

    @Test
    void securityTagExemptionHoldsAgainstALiveSwitch() {
        long projectId = createProject("qa-exempt");
        long caseId = pendingCase(projectId, 1, 3, CaseStatus.PENDING_REVIEW.code());
        bindTag(projectId, caseId, "安全类");
        switchOn();

        policy.tryAutoApproveAfterReproof(caseId, 1L);

        assertEquals(CaseStatus.PENDING_REVIEW.code(), status(caseId),
                "the security family is exempt no matter how the switch is set");
        assertEquals(0, autoPassReviewLogs(caseId));
    }

    @Test
    void gradeFloorIsHotConfigurableAndIllegalShapesFallBack() {
        long projectId = createProject("qa-hot");
        long grade2 = pendingCase(projectId, 1, 2, CaseStatus.PENDING_REVIEW.code());
        long grade2Again = pendingCase(projectId, 1, 2, CaseStatus.PENDING_REVIEW.code());
        switchOn();
        configCacheHolder.put(AiReviewAutoApprovePolicy.KEY_MIN_GRADE, "2");

        policy.tryAutoApproveAfterReproof(grade2, 1L);
        assertEquals(CaseStatus.APPROVED.code(), status(grade2),
                "lowering the floor to 2 takes effect live, no restart");

        configCacheHolder.put(AiReviewAutoApprovePolicy.KEY_MIN_GRADE, "not-a-number");
        policy.tryAutoApproveAfterReproof(grade2Again, 1L);
        assertEquals(CaseStatus.PENDING_REVIEW.code(), status(grade2Again),
                "an illegal floor falls back to the shipped 3, never to zero");
    }

    @Test
    void reproofPassDrivesTheWholeChannelWhenEnabled() {
        long projectId = createProject("qa-chain");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url)"
                        + " VALUES (?, ?, 1, ?)",
                projectId, "qa-chain-test", "https://qa-chain.reproof.invalid");
        long caseId = ScriptWritebackDrafts.createCase(caseService, projectId);
        jdbcTemplate.update("UPDATE tp_test_case SET confidence = 1 WHERE id = ?", caseId);
        switchOn();

        scriptWritebackService.writeback(new ScriptWritebackInput(caseId, "v2",
                ScriptWritebackDrafts.ACTION_JSON, ScriptWritebackDrafts.evidence(), null, 1L));
        long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE case_id = ? AND batch_id IS NULL"
                        + " ORDER BY id DESC LIMIT 1", Long.class, caseId);
        trackedTasks.add(taskId);

        platformReproof.onResultSubmitted(execTaskService.get(taskId), "pass");

        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT evidence_grade FROM tp_test_case WHERE id = ?", Integer.class, caseId));
        assertEquals(CaseStatus.APPROVED.code(), status(caseId),
                "settle point raised the grade and the channel consumed it in one motion");
        assertEquals(1, autoPassReviewLogs(caseId));
    }
}
