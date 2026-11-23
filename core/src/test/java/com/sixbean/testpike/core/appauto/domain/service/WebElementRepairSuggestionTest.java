package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoStepHealingService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.webelement.api.dto.WebElementAnchorInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementInput;
import com.sixbean.testpike.core.webelement.api.dto.WebElementView;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WE batch S2 Web element repair suggestions on the A9 board.
 * <p>Verified doors: record-side WEB validation (element required, ledger must
 * exist and live in the SAME project as the failing task, anchor JSON type from
 * the ledger value domain, non-blank expression, no action change smuggled in,
 * failure-class gate shared with APP), adoption (ledger optimistic lock is the
 * revision token, proposed anchor lands first with equal anchors de-duplicated,
 * candidate row revives to confirmed, adopted revision backfill, processed rows
 * refuse re-adoption) and the shared healing switch (closed = 403, zero writes).
 * <p>Real dev DB through the Spring context (no mocks). APP-shape regressions
 * stay in AppautoStepHealingServiceTest; this class owns the WEB dimension.
 */
@SpringBootTest(classes = CoreApplication.class)
class WebElementRepairSuggestionTest {

    private static final String HEALING_KEY = "ai.appauto.step-healing";

    @Autowired
    private AppautoStepHealingService healingService;

    @Autowired
    private WebElementService webElementService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long caseId;
    private long taskId;
    private long elementId;

    @BeforeEach
    void setUp() {
        String code = "wsh" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "元素修复建议测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, 1, 4, 1, '{"version":"v2","steps":[]}')
                """, projectId);
        taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE project_id = ?", Long.class, projectId)
                .longValue();
        elementId = webElementService.create(new WebElementInput(projectId, "登录按钮",
                "http://target.local/login",
                List.of(new WebElementAnchorInput("css", "#old-btn", 0.4, "改版前锚")))).id();
        cleanTables();
        configCacheHolder.put(HEALING_KEY, "0");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(HEALING_KEY);
        cleanTables();
        jdbcTemplate.update("DELETE FROM tp_web_element WHERE id = ?", elementId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_appauto_step_suggestion WHERE project_id = ?",
                projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_ai_sample WHERE project_id = ?", projectId);
    }

    private AppautoStepSuggestionInput webProposal(Long element, String action, String target,
                                                   Integer failureClass, String source) {
        return new AppautoStepSuggestionInput(taskId, 1, failureClass, "click:登录按钮",
                source, action, target, "DOM 快照比对：旧 css 锚失配，testid 强锚在位",
                null, null, "WEB", element);
    }

    private long seedWebSuggestion() {
        return healingService.record(webProposal(elementId, null,
                "{\"type\":\"testid\",\"value\":\"login-btn\",\"strength\":0.9}", 2, "dom"));
    }

    @Test
    void webSuggestionStoresObjectDimensionAndLedgerTarget() {
        long id = seedWebSuggestion();
        AppautoStepSuggestionView row = healingService.find(id);
        assertEquals("WEB", row.objectType());
        assertEquals(elementId, row.elementId().longValue());
        assertEquals(1, row.status());
    }

    @Test
    void webRowWithoutLedgerTargetRefuses() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.record(webProposal(null, null,
                        "{\"type\":\"testid\",\"value\":\"login-btn\",\"strength\":0.9}",
                        2, "dom")));
        assertTrue(ex.getMessage().contains("账本条目"), ex.getMessage());
    }

    @Test
    void webRowAgainstUnknownLedgerEntryRefuses() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.record(webProposal(999_999_999L, null,
                        "{\"type\":\"testid\",\"value\":\"x\",\"strength\":0.9}", 2, "dom")));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void webRowCrossingProjectBoundaryRefuses() {
        String other = "wshX" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "越权对照项目-" + other, other);
        long foreignProject = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, other);
        long foreignElement = webElementService.create(new WebElementInput(foreignProject,
                "外来按钮", "http://target.local/other",
                List.of(new WebElementAnchorInput("css", "#x", 0.5, null)))).id();
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> healingService.record(webProposal(foreignElement, null,
                            "{\"type\":\"testid\",\"value\":\"y\",\"strength\":0.9}", 2, "dom")));
            assertTrue(ex.getMessage().contains("项目"), ex.getMessage());
        } finally {
            jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                    + " AND job_ref = ?", String.valueOf(foreignElement));
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE id = ?", foreignElement);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", foreignProject);
        }
    }

    @Test
    void webRowWithAnchorTypeOutsideValueDomainRefuses() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.record(webProposal(elementId, null,
                        "{\"type\":\"selector\",\"value\":\"x\"}", 2, "dom")));
        assertTrue(ex.getMessage().contains("锚类型"), ex.getMessage());
    }

    @Test
    void webRowWithBlankAnchorExpressionRefuses() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.record(webProposal(elementId, null,
                        "{\"type\":\"testid\",\"value\":\"  \"}", 2, "dom")));
        assertTrue(ex.getMessage().contains("不能为空"), ex.getMessage());
    }

    @Test
    void webRowSmugglingActionChangeRefuses() {
        // Red line 1 (equivalent re-location only): a WEB proposal never edits
        // the step action; carrying one refuses the whole record.
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.record(webProposal(elementId, "inputText",
                        "{\"type\":\"testid\",\"value\":\"x\",\"strength\":0.9}", 2, "dom")));
        assertTrue(ex.getMessage().contains("动作"), ex.getMessage());
    }

    @Test
    void failureClassOutsideUiChangeRefusesForWebToo() {
        // Red line 3: attribution 1 (product defect) must not enter any healing
        // chain, both dimensions share this gate.
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.record(webProposal(elementId, null,
                        "{\"type\":\"testid\",\"value\":\"x\",\"strength\":0.9}", 1, "dom")));
        assertTrue(ex.getMessage().contains("产品缺陷"), ex.getMessage());
    }

    @Test
    void closedSwitchRefusesWebAdoptionWithoutTouchingLedger() {
        long id = seedWebSuggestion();
        WebElementView before = webElementService.findById(elementId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.adopt(1L, id, before.revision()));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertEquals(1, healingService.find(id).status());
        assertEquals(before.revision(), webElementService.findById(elementId).revision());
    }

    @Test
    void adoptionMergesAnchorFirstRevivesCandidateAndBackfillsRevision() {
        long id = seedWebSuggestion();
        configCacheHolder.put(HEALING_KEY, "1");
        WebElementView before = webElementService.findById(elementId);
        AppautoStepSuggestionView adopted = healingService.adopt(1L, id, before.revision());

        assertEquals(2, adopted.status());
        assertNotNull(adopted.objectType());
        WebElementView after = webElementService.findById(elementId);
        assertEquals(1, after.state(), "approved anchor revives the candidate row");
        assertEquals("testid", after.anchors().get(0).type());
        assertEquals("login-btn", after.anchors().get(0).value());
        assertTrue(after.revision() > before.revision());
        String board = jdbcTemplate.queryForObject(
                "SELECT adopted_element_revision::text FROM tp_appauto_step_suggestion WHERE id = ?",
                String.class, id);
        assertEquals(String.valueOf(after.revision()),
                board == null ? "null" : String.valueOf(Integer.parseInt(board)));
    }

    @Test
    void adoptionDeduplicatesTheEquivalentOldAnchor() {
        // Ledger already holds the proposed anchor mid-list: adoption keeps one
        // copy and moves it to the front (first-try ordering is runtime law).
        WebElementView before = webElementService.findById(elementId);
        webElementService.updateAnchors(elementId, List.of(
                new WebElementAnchorInput("css", "#old-btn", 0.4, "改版前锚"),
                new WebElementAnchorInput("testid", "login-btn", 0.9, "已见候选")),
                before.revision());
        long id = seedWebSuggestion();
        configCacheHolder.put(HEALING_KEY, "1");
        WebElementView preAdopt = webElementService.findById(elementId);

        healingService.adopt(1L, id, preAdopt.revision());
        WebElementView after = webElementService.findById(elementId);
        assertEquals(2, after.anchors().size());
        assertEquals("testid", after.anchors().get(0).type());
        assertEquals("css", after.anchors().get(1).type());
    }

    @Test
    void staleRevisionRefusesAdoptionAndKeepsSuggestionRetryable() {
        long id = seedWebSuggestion();
        configCacheHolder.put(HEALING_KEY, "1");
        WebElementView before = webElementService.findById(elementId);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.adopt(1L, id, before.revision() + 7));
        assertEquals(BizErrorCode.VERSION_CONFLICT, ex.getCode());
        assertEquals(1, healingService.find(id).status(), "refused adoption leaves the row pending");

        AppautoStepSuggestionView adopted = healingService.adopt(1L, id, before.revision());
        assertEquals(2, adopted.status(), "retry with the fresh token succeeds");
    }

    @Test
    void processedSuggestionRefusesSecondAdoption() {
        long id = seedWebSuggestion();
        configCacheHolder.put(HEALING_KEY, "1");
        healingService.adopt(1L, id, webElementService.findById(elementId).revision());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> healingService.adopt(1L, id, webElementService.findById(elementId).revision()));
        assertTrue(ex.getMessage().contains("已采纳"), ex.getMessage());
    }

    @Test
    void appDimensionRowsKeepTheirLegacyDefaults() {
        // Zero-drift guard: legacy 10-arg constructor still lands APP rows.
        long id = healingService.record(new AppautoStepSuggestionInput(taskId, 1, 2,
                "tap:登录按钮", "vision", "app.tap", "{\"content-desc\":\"登录\"}", null, 10, 10));
        AppautoStepSuggestionView row = healingService.find(id);
        assertEquals("APP", row.objectType());
    }
}
