package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStepSuggestionView;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoStepHealingServiceTest {

    private static final String HEALING_KEY = "ai.appauto.step-healing";

    private static final String APP_SCRIPT =
            "{\"version\":\"v2\",\"steps\":["
                    + "{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}},"
                    + "{\"action\":\"app.tap\",\"target\":{\"resourceId\":\"com.demo:id/old\"}}]}";

    @Autowired
    private AppautoStepHealingService healingService;

    @Autowired
    private AppautoAiSampleService aiSampleService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long caseId;
    private long taskId;

    @BeforeEach
    void setUp() {
        String code = "sh" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "自愈建议测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        caseId = caseService.create(1L, new CaseCreateInput(projectId, "自愈用例", "前置", "预期",
                1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "app", "MOBILEWRIGHT", null)).id();
        scriptService.save(new ScriptSaveInput(caseId, "v2", APP_SCRIPT, 2));
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, ?, 4, 6, '{"kind":"app-auto-run","steps":[]}')
                """, projectId, caseId);
        taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE project_id = ? AND case_id = ?",
                Long.class, projectId, caseId).longValue();
        cleanTables();
        configCacheHolder.put(HEALING_KEY, "0");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(HEALING_KEY);
        cleanTables();
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_appauto_step_suggestion WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_ai_sample WHERE project_id = ?", projectId);
    }

    private AppautoStepSuggestionInput proposal(Integer stepNo, Integer failureClass, String anchor,
                                                String source, String action, String target,
                                                Integer x, Integer y) {
        return new AppautoStepSuggestionInput(taskId, stepNo, failureClass, anchor, source,
                action, target, "视图树比对：旧 resourceId 已失效，候选 content-desc 定位", x, y);
    }

    private long seedPendingProposal() {
        return healingService.record(proposal(2, 2, "tap:登录按钮", "vision",
                "app.tap", "{\"content-desc\":\"登录\"}", 120, 640));
    }

    @Test
    void 迁移两件在位且开关默认关() {
        Integer table = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?",
                Integer.class, "tp_appauto_step_suggestion");
        assertEquals(1, table, "V159 建议单表必须已手动落库");
        String seed = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, HEALING_KEY);
        assertEquals("1", seed, "V160 键必须落库且默认开（AI 功能出厂默认=开，2026-10-26 裁决翻值批）");
    }

    @Test
    void record三值域闸逐项人话拒() {
        ServiceException e1 = assertThrows(ServiceException.class, () ->
                healingService.record(proposal(2, 1, "tap:锚", "vision",
                        "app.tap", "{\"a\":1}", null, null)));
        assertTrue(e1.getMessage().contains("UI"), e1.getMessage());
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                healingService.record(proposal(2, 2, "tap:锚", "vision",
                        "app.teleport", "{\"a\":1}", null, null)));
        assertTrue(e2.getMessage().contains("app.teleport"), e2.getMessage());
        assertThrows(ServiceException.class, () ->
                healingService.record(proposal(2, 2, "  ", "vision",
                        "app.tap", "{\"a\":1}", null, null)));
        assertThrows(ServiceException.class, () ->
                healingService.record(proposal(2, 2, "tap:锚", "guess",
                        "app.tap", "{\"a\":1}", null, null)));
        assertThrows(ServiceException.class, () ->
                healingService.record(proposal(2, 2, "tap:锚", "vision",
                        "app.tap", "not-json", null, null)));
        assertEquals(0, count("tp_appauto_step_suggestion"));
    }

    @Test
    void taskId权威归属伪造不入契约() {
        long id = seedPendingProposal();
        List<AppautoStepSuggestionView> rows = healingService.list(projectId, caseId, null, 50);
        assertEquals(1, rows.size());
        AppautoStepSuggestionView view = rows.get(0);
        assertEquals(id, view.id());
        assertEquals(projectId, view.projectId(), "项目归属必须按任务行权威反查");
        assertEquals(caseId, view.caseId());
        assertEquals(taskId, view.sourceTaskId());
        assertEquals(1, view.status(), "落行默认待批");
        assertEquals("tap:登录按钮", view.anchor());
    }

    @Test
    void 关态采纳人话拒零写回() {
        long id = seedPendingProposal();
        ScriptInfo before = scriptService.getByCaseId(caseId);
        ServiceException e = assertThrows(ServiceException.class, () ->
                healingService.adopt(1L, id, before.version()));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("ai.appauto.step-healing"), e.getMessage());
        ScriptInfo after = scriptService.getByCaseId(caseId);
        assertEquals(before.version(), after.version(), "关态脚本版本必不动");
        assertEquals(1, healingStatus(id), "关态建议单状态必不翻");
        assertEquals(0, count("tp_appauto_ai_sample"), "关态样本零回灌");
    }

    @Test
    void 开态采纳真实落版回灌样本() {
        openGate();
        long id = seedPendingProposal();
        ScriptInfo before = scriptService.getByCaseId(caseId);
        healingService.adopt(1L, id, before.version());

        ScriptInfo after = scriptService.getByCaseId(caseId);
        assertEquals(before.version() + 1, after.version(), "采纳=脚本真实落版+1");
        assertTrue(after.actionJson().contains("content-desc"), "目标步新定位必须生效");
        assertTrue(after.actionJson().contains("app.tap"));
        assertFalse(after.actionJson().contains("com.demo:id/old"), "旧定位必须被替换");
        List<String> reasons = jdbcTemplate.queryForList(
                "SELECT reason FROM tp_script_version WHERE case_id = ? ORDER BY id DESC LIMIT 1",
                String.class, caseId);
        assertEquals("AI修复", reasons.get(0), "版本线理由必须复用 AI 修复单点");
        assertEquals(2, healingStatus(id), "采纳后状态翻 2");
        assertEquals(after.version(), healingAdoptedVersion(id).intValue());
        List<Long> sampleIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_appauto_ai_sample WHERE case_id = ?", Long.class, caseId);
        assertEquals(1, sampleIds.size(), "带坐标采纳必回灌一条 confirmed 样本");
        String result = jdbcTemplate.queryForObject(
                "SELECT review_result FROM tp_appauto_ai_sample WHERE case_id = ?",
                String.class, caseId);
        assertEquals("confirmed", result);
    }

    @Test
    void 陈旧期望版本人话拒可重试() {
        openGate();
        long id = seedPendingProposal();
        ScriptInfo current = scriptService.getByCaseId(caseId);
        ServiceException e = assertThrows(ServiceException.class, () ->
                healingService.adopt(1L, id, current.version() + 5));
        assertEquals(BizErrorCode.VERSION_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains(String.valueOf(current.version())),
                "409 人话必含当前版本：" + e.getMessage());
        assertEquals(1, healingStatus(id), "版本冲突不烧单，仍可重试");
        healingService.adopt(1L, id, current.version());
        assertEquals(2, healingStatus(id), "正版本重试必成");
    }

    @Test
    void 已处理后重复审批人话拒脚本零触碰() {
        openGate();
        long adopted = seedPendingProposal();
        healingService.adopt(1L, adopted, scriptService.getByCaseId(caseId).version());
        ScriptInfo afterAdopt = scriptService.getByCaseId(caseId);
        assertThrows(ServiceException.class, () ->
                healingService.adopt(1L, adopted, afterAdopt.version()));
        assertEquals(afterAdopt.version(), scriptService.getByCaseId(caseId).version(),
                "重复采纳脚本必零触碰");

        long rejected = seedPendingProposal();
        healingService.reject(1L, rejected);
        assertThrows(ServiceException.class, () -> healingService.reject(1L, rejected));
        assertThrows(ServiceException.class, () ->
                healingService.adopt(1L, rejected, afterAdopt.version()));
        assertEquals(3, healingStatus(rejected));
        assertEquals(afterAdopt.version(), scriptService.getByCaseId(caseId).version());
    }

    private void openGate() {
        configCacheHolder.put(HEALING_KEY, "1");
    }

    private int healingStatus(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tp_appauto_step_suggestion WHERE id = ?",
                Integer.class, id).intValue();
    }

    private Integer healingAdoptedVersion(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT adopted_script_version FROM tp_appauto_step_suggestion WHERE id = ?",
                Integer.class, id);
    }

    private int count(String table) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }
}
