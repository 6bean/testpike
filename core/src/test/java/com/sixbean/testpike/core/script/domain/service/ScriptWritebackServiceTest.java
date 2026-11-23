package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackResult;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackEvidence;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.ScriptWritebackDrafts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Off-agent script write-back service contract (real database, no mocks).
 * <p>Why this gate exists: an outside coding agent returns a repaired script plus
 * an optional evidence package. The platform must (1) land it as a new script
 * version through the single save path, (2) pull the case back to pending-review
 * so a human always re-signs off, (3) grade the case by whether evidence was
 * attached (self-report vs evidence on file), (4) refuse to overwrite an
 * already-approved case and name the channel to use instead, and (5) reject a
 * stale expected script version so concurrent edits cannot trample each other.
 * <p>Bidirectional fixtures: every rejection assertion is paired with the legal
 * counterpart passing in the same test, so a guard accidentally removed cannot
 * keep the suite green (no silent no-op detection).
 */
@SpringBootTest(classes = CoreApplication.class)
class ScriptWritebackServiceTest {

    @Autowired
    private ScriptWritebackService scriptWritebackService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> trackedProjects = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
    }

    private static final String ACTION_JSON = ScriptWritebackDrafts.ACTION_JSON;

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "写回项目" + code, code);
    }

    private long createCase(long projectId) {
        return ScriptWritebackDrafts.createCase(caseService, projectId);
    }

    private static ScriptWritebackEvidence evidence() {
        return ScriptWritebackDrafts.evidence();
    }

    private Map<String, Object> caseRow(long caseId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, evidence_grade, version FROM tp_test_case WHERE id = ?", caseId);
    }

    @Test
    void writebackWithEvidenceLandsNewVersionAndGradesCaseEvidenceOnFile() {
        long projectId = createProject("WBT1");
        long caseId = createCase(projectId);

        ScriptWritebackResult result = scriptWritebackService.writeback(
                new ScriptWritebackInput(caseId, "v2", ACTION_JSON, evidence(), null, 1L));

        assertEquals(1, result.scriptVersion());
        assertEquals(1, result.caseStatus());   // pulled back to pending review
        assertEquals(2, result.evidenceGrade()); // evidence package on file

        Map<String, Object> row = caseRow(caseId);
        assertEquals(1, ((Number) row.get("status")).intValue());
        assertEquals(2, ((Number) row.get("evidence_grade")).shortValue());

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'script' "
                        + "AND object_id = ? AND action = 'script_writeback'",
                Integer.class, String.valueOf(caseId));
        assertEquals(1, auditCount);
        String afterJson = jdbcTemplate.queryForObject(
                "SELECT after_json FROM tp_audit_log WHERE object_type = 'script' "
                        + "AND object_id = ? AND action = 'script_writeback' ORDER BY id DESC LIMIT 1",
                String.class, String.valueOf(caseId));
        assertTrue(afterJson.contains("6f2c1a9e"), "audit must carry the log digest");
    }

    @Test
    void bareReportWithoutEvidenceGradesCaseSelfReport() {
        long projectId = createProject("WBT2");
        long caseId = createCase(projectId);

        ScriptWritebackResult result = scriptWritebackService.writeback(
                new ScriptWritebackInput(caseId, "v2", ACTION_JSON, null, null, 1L));

        assertEquals(1, result.evidenceGrade()); // self-declared only
        assertEquals(1, ((Number) caseRow(caseId).get("evidence_grade")).shortValue());
    }

    @Test
    void writebackResetsStalePlatformValidationGradeToNewVersionLevel() {
        long projectId = createProject("WBT3");
        long caseId = createCase(projectId);
        // Fixture: pretend a previous version passed platform re-validation (grade 3).
        jdbcTemplate.update("UPDATE tp_test_case SET evidence_grade = 3 WHERE id = ?", caseId);

        ScriptWritebackResult result = scriptWritebackService.writeback(
                new ScriptWritebackInput(caseId, "v2", ACTION_JSON, null, null, 1L));

        assertEquals(1, result.evidenceGrade(),
                "a freshly written-back version was never platform-validated; grade must not stay 3");
    }

    @Test
    void approvedCaseIsRefusedWithStatusAndChannelGuidanceAndNothingIsWritten() {
        long projectId = createProject("WBT4");
        long caseId = createCase(projectId);
        // Fixture: approved status on the live state machine (code 3).
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", caseId);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptWritebackService.writeback(
                        new ScriptWritebackInput(caseId, "v2", ACTION_JSON, evidence(), null, 1L)));

        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("已通过"), "message must name the current status");
        assertTrue(ex.getMessage().contains("退回") || ex.getMessage().contains("草案"),
                "message must name the usable channel instead of a generic failure");

        Integer scripts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script WHERE case_id = ?", Integer.class, caseId);
        assertEquals(0, scripts); // guard runs before any write
        assertEquals(0, ((Number) caseRow(caseId).get("evidence_grade")).shortValue());

        // Counterpart: pulling the case back to pending review makes the same call succeed.
        jdbcTemplate.update("UPDATE tp_test_case SET status = 4 WHERE id = ?", caseId);
        ScriptWritebackResult ok = scriptWritebackService.writeback(
                new ScriptWritebackInput(caseId, "v2", ACTION_JSON, evidence(), null, 1L));
        assertEquals(1, ok.scriptVersion());
    }

    @Test
    void staleExpectedScriptVersionIsRejectedAndFreshOneAccepted() {
        long projectId = createProject("WBT5");
        long caseId = createCase(projectId);
        scriptWritebackService.writeback(
                new ScriptWritebackInput(caseId, "v2", ACTION_JSON, null, null, 1L)); // v1

        // Legal side: expecting the current version 1 lands version 2.
        ScriptWritebackResult fresh = scriptWritebackService.writeback(
                new ScriptWritebackInput(caseId, "v2", ACTION_JSON.replace("登录成功", "登录成功v2"), null, 1, 1L));
        assertEquals(2, fresh.scriptVersion());

        // Stale side: version 1 was already superseded, the write must be refused.
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptWritebackService.writeback(
                        new ScriptWritebackInput(caseId, "v2", ACTION_JSON, null, 1, 1L)));
        assertEquals(BizErrorCode.VERSION_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("2"), "conflict message must name the current version");

        Integer scripts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script WHERE case_id = ?", Integer.class, caseId);
        assertEquals(1, scripts); // still the single 1:1 row, no partial write
        Integer maxVersion = jdbcTemplate.queryForObject(
                "SELECT max(version) FROM tp_script WHERE case_id = ?", Integer.class, caseId);
        assertEquals(2, maxVersion); // the rejected stale write left no trace
    }
}
