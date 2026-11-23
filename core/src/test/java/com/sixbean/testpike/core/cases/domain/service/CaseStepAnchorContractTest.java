package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step-level anchor storage contract on tp_case_step (WY batch report A).
 * <p>Why this gate exists: AI-generated steps currently carry no traceable
 * evidence of which page element / requirement block they came from, so the
 * review UI cannot answer "why this step" (R63 traceability surface). The
 * anchor column (V624) plus the single-source write chain must guarantee:
 * anchors land, manual edits without anchor evidence preserve the old anchor
 * at column level (never silently dropped), replaying an identical step set
 * writes nothing (R28), and malformed anchors are refused with the
 * three-element guidance sentence (cause names the offending value, fix
 * names the entry point, correct form example).
 * <p>All paths run against the live slot database through the real
 * CaseService chain (no mock); the class is transactional so factory rows
 * keep zero residue (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
@Transactional
class CaseStepAnchorContractTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CaseServiceImpl caseService;

    private long projectId;

    @BeforeEach
    void seedProject() {
        projectId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 930000 FROM tp_project", Long.class);
        jdbcTemplate.update("INSERT INTO tp_project (id, name, code) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?)",
                projectId, "anchor seed project", "ANCH-P-SEED-" + projectId);
    }

    @Test
    void anchorColumnIsNullableJsonbOnCaseStep() {
        Map<String, Object> col = jdbcTemplate.queryForMap(
                "select data_type, is_nullable from information_schema.columns "
                        + "where table_name = 'tp_case_step' and column_name = 'anchor'");
        assertEquals("jsonb", col.get("data_type"));
        assertEquals("YES", col.get("is_nullable"));
    }

    @Test
    void createPersistsExplicitAnchorAndDerivesElemDialectAnchor() {
        CaseInfo created = caseService.create(1L, createInput(List.of(
                new CaseStepInput(1, "打开登录页", 0,
                        "{\"kind\":\"source\",\"documentId\":123,\"chunkIds\":[456,789]}"),
                new CaseStepInput(2, "点击 {{ELEM:login-btn}} 按钮", 1, null))));
        List<CaseStepInfo> steps = caseService.get(created.id()).steps();
        assertEquals(2, steps.size());
        assertNotNull(steps.get(0).anchor(), "explicit anchor must land");
        assertTrue(steps.get(0).anchor().contains("456"), "chunk ids must survive jsonb round-trip");
        assertTrue(steps.get(1).anchor().contains("login-btn"),
                "{{ELEM:name}} dialect in the description must derive the element reference anchor");
    }

    @Test
    void illegalAnchorShapeIsRefusedWithThreeElementGuidance() {
        for (String bad : List.of("not-a-json", "{\"kind\":\"unknown-kind\"}", "[1,2]")) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> caseService.create(1L, createInput(
                            List.of(new CaseStepInput(1, "任意步骤", 0, bad)))),
                    "anchor payload must be refused: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode().intValue());
            String msg = ex.getMessage();
            assertTrue(msg.contains(bad.length() > 24 ? bad.substring(0, 24) : bad) || msg.contains("anchor"),
                    "guidance must name the offending value, got: " + msg);
            assertTrue(msg.contains("步骤") && (msg.contains("用例详情") || msg.contains("草案")),
                    "guidance must point at the fix entry, got: " + msg);
            assertTrue(msg.contains("正确写法示例") || msg.contains("示例"),
                    "guidance must show the correct form, got: " + msg);
        }
    }

    @Test
    void manualEditWithoutAnchorKeepsPreviousAnchorAtColumnLevel() {
        CaseInfo created = caseService.create(1L, createInput(List.of(
                new CaseStepInput(1, "第一步", 0, "{\"kind\":\"elem\",\"ref\":\"login-btn\"}"))));
        String beforeAnchor = caseService.get(created.id()).steps().get(0).anchor();
        assertNotNull(beforeAnchor);

        // The edit chain (manual UI) sends the 3-arg shape: no anchor knowledge at all.
        caseService.update(1L, created.id(), new CaseUpdateInput(null, null, null, null, null, null, null,
                List.of(new CaseStepInput(1, "第一步（改写描述）", 0))), created.version());

        CaseStepInfo after = caseService.get(created.id()).steps().get(0);
        assertEquals(beforeAnchor, after.anchor(),
                "anchor must survive a manual step edit that carries no anchor evidence");
    }

    @Test
    void replayingIdenticalStepSetWritesNothing() {
        List<CaseStepInput> steps = List.of(
                new CaseStepInput(1, "打开首页", 0, "{\"kind\":\"contract\",\"matchKey\":\"GET /api/v1/case\"}"),
                new CaseStepInput(2, "校验列表非空", 1, null));
        CaseInfo created = caseService.create(1L, createInput(steps));
        LocalDateTime caseUpdatedAt = caseService.get(created.id()).updatedAt();
        List<Long> stepIds = stepRowIds(created.id());
        LocalDateTime stepCreatedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM tp_case_step WHERE case_id = ? ORDER BY step_no LIMIT 1",
                LocalDateTime.class, created.id());

        caseService.update(1L, created.id(), new CaseUpdateInput(null, null, null, null, null, null, null, steps),
                created.version());
        CaseInfo afterReplay = caseService.get(created.id());

        assertEquals(stepIds, stepRowIds(afterReplay.id()),
                "identical replay must not delete+reinsert step rows (R28 zero-write idempotency)");
        assertEquals(caseUpdatedAt, afterReplay.updatedAt(),
                "identical replay must not bump tp_test_case.updated_at");
        assertEquals(stepCreatedAt, jdbcTemplate.queryForObject(
                "SELECT created_at FROM tp_case_step WHERE case_id = ? ORDER BY step_no LIMIT 1",
                LocalDateTime.class, afterReplay.id()));
    }

    @Test
    void legacyAnchorFreeStepStaysNullOnReadModel() {
        CaseInfo created = caseService.create(1L, createInput(
                List.of(new CaseStepInput(1, "纯手工步骤", 0))));
        CaseStepInfo step = caseService.get(created.id()).steps().get(0);
        assertNull(step.anchor(), "manual steps without evidence keep anchor NULL (no backfill)");
    }

    private CaseCreateInput createInput(List<CaseStepInput> steps) {
        return new CaseCreateInput(projectId, "锚点契约用例", "前置", "预期",
                1, 2, "web", 1, null, null, null, steps);
    }

    private List<Long> stepRowIds(long caseId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_case_step WHERE case_id = ? ORDER BY step_no", Long.class, caseId);
    }
}
