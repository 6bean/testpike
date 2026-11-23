package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftStepInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordDraftView;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoRecordDraftServiceTest {

    private static final String RECORD_KEY = "ai.appauto.record-to-flow";

    private static final String BASE_SCRIPT =
            "{\"version\":\"v2\",\"steps\":["
                    + "{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}}]}";

    @Autowired
    private AppautoRecordDraftService draftService;

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
        String code = "rd" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "录制草稿测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        caseId = caseService.create(1L, new CaseCreateInput(projectId, "录制用例", "前置", "预期",
                1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "app", "MOBILEWRIGHT", null)).id();
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, ?, 4, 6, '{"kind":"app-auto-run","steps":[]}')
                """, projectId, caseId);
        taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE project_id = ? AND case_id = ?",
                Long.class, projectId, caseId).longValue();
        cleanTables();
        configCacheHolder.put(RECORD_KEY, "0");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(RECORD_KEY);
        cleanTables();
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_appauto_record_draft WHERE project_id = ?", projectId);
    }

    private void enableRecordGate() {
        configCacheHolder.put(RECORD_KEY, "1");
    }

    private AppautoRecordDraftInput draftInput(String eventSource, String title,
                                               List<AppautoRecordDraftStepInput> steps) {
        return new AppautoRecordDraftInput(taskId, eventSource, title, steps);
    }

    private AppautoRecordDraftStepInput step(Integer stepNo, String action, String target, String reason) {
        return new AppautoRecordDraftStepInput(stepNo, action, target, null, reason);
    }

    private long seedDraft() {
        return draftService.record(draftInput("native", "登录流程录制", List.of(
                step(1, "app.tap", "{\"resourceId\":\"com.demo:id/user\"}", "点账号框"),
                step(2, "app.input", "{\"resourceId\":\"com.demo:id/pwd\"}", "输密码"),
                step(3, "app.tap", "{\"content-desc\":\"登录\"}", "点登录钮"))));
    }


    @Test
    void migrationArtifactsInPlace() {
        Integer cols = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'tp_appauto_record_draft'",
                Integer.class);
        assertNotNull(cols);
        assertTrue(cols >= 11, "V169 草稿表列不齐");
        List<String> keys = jdbcTemplate.queryForList("""
                SELECT config_key FROM tp_sys_config WHERE config_key IN
                ('ai.appauto.record-to-flow','ai.appauto.gen-steps','ai.appauto.wx-mock')
                """, String.class);
        assertEquals(3, keys.size(), "V168 三开关键不齐");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_sys_config WHERE config_key IN "
                        + "('ai.appauto.record-to-flow','ai.appauto.gen-steps','ai.appauto.wx-mock')"
                        + " AND config_value <> '1'", Integer.class).intValue(),
                "三键默认必须开（AI 功能出厂默认=开，2026-10-26 裁决翻值批）");
    }


    @Test
    void recordRejectedWhenGateClosed() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> seedDraft());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("录制转流程当前未开启：请在「系统管理 → 系统配置」搜索")
                && ex.getMessage().contains("ai.appauto.record-to-flow"), "关态拒绝须点名功能与配置键");
        assertEquals(0, countDrafts());
    }


    @Test
    void recordPopulatesOwnershipFromTask() {
        enableRecordGate();
        long id = seedDraft();
        assertTrue(id > 0);
        AppautoRecordDraftView row = draftService.find(id);
        assertEquals(projectId, row.projectId(), "归属项目必须由 taskId 反查权威补入");
        assertEquals(caseId, row.caseId());
        assertEquals(taskId, row.sourceTaskId());
        assertEquals("native", row.eventSource());
        assertEquals(1, row.status(), "新草稿必须待审");
        String items = jdbcTemplate.queryForObject(
                "SELECT items::text FROM tp_appauto_record_draft WHERE id = ?", String.class, id);
        assertNotNull(items);
        assertTrue(items.contains("app.input") && items.contains("登录钮"), "草稿步须 JSONB 全量留痕");
        assertEquals("jsonb", jdbcTemplate.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_name = 'tp_appauto_record_draft' AND column_name = 'items'
                """, String.class));
    }


    @Test
    void recordValidationRejectsBadPayloads() {
        enableRecordGate();
        ServiceException badSource = assertThrows(ServiceException.class,
                () -> draftService.record(draftInput("getevent", "档位越域",
                        List.of(step(1, "app.tap", "{}", null)))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, badSource.getCode());
        assertTrue(badSource.getMessage().contains("native"));

        ServiceException emptySteps = assertThrows(ServiceException.class,
                () -> draftService.record(draftInput("native", "空步", List.of())));
        assertTrue(emptySteps.getMessage().contains("草稿步"));

        ServiceException badVerb = assertThrows(ServiceException.class,
                () -> draftService.record(draftInput("native", "越基线",
                        List.of(step(1, "app.dance", "{}", null)))));
        assertTrue(badVerb.getMessage().contains("app.dance"), "越基线动词须点名");

        ServiceException missingStepNo = assertThrows(ServiceException.class,
                () -> draftService.record(draftInput("native", "缺步序",
                        List.of(step(null, "app.tap", "{}", null)))));
        assertTrue(missingStepNo.getMessage().contains("步序"));

        ServiceException badTarget = assertThrows(ServiceException.class,
                () -> draftService.record(draftInput("native", "目标非对象",
                        List.of(step(1, "app.tap", "\"just-a-string\"", null)))));
        assertTrue(badTarget.getMessage().contains("定位策略"));
        assertEquals(0, countDrafts(), "闸拒的登记必须零行落");
    }


    @Test
    void confirmRejectedWhenGateClosed() {
        enableRecordGate();
        long id = seedDraft();
        configCacheHolder.put(RECORD_KEY, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        ServiceException ex = assertThrows(ServiceException.class,
                () -> draftService.confirm(1L, id, List.of(1, 2), null));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertNull(scriptService.getByCaseId(caseId), "关态入库不可触碰脚本");
        assertEquals(1, draftService.find(id).status(), "关态拒绝后草稿仍待审");
    }


    @Test
    void confirmCreatesScriptFromCheckedSteps() {
        enableRecordGate();
        long id = seedDraft();
        AppautoRecordDraftView done = draftService.confirm(1L, id, List.of(1, 3), null);
        assertEquals(2, done.status(), "勾选入库后状态翻已入库");
        assertEquals(1, done.scriptVersion(), "落版序回填留痕");
        ScriptInfo script = scriptService.getByCaseId(caseId);
        assertNotNull(script, "勾选的草稿步必须真实落脚本");
        assertEquals(3, script.generatedBy(), "录制入库落版=3 录制");
        assertTrue(script.actionJson().contains("com.demo:id/user"));
        assertTrue(script.actionJson().contains("登录"));
        assertFalse(script.actionJson().contains("app.input"), "未勾选步零落版");
        assertEquals(2, stepCount(script.actionJson()));
    }


    @Test
    void confirmAppendsToExistingScriptAndGuardsStaleVersion() {
        enableRecordGate();
        scriptService.save(new ScriptSaveInput(caseId, "v2", BASE_SCRIPT, 2));
        long id = seedDraft();
        ServiceException stale = assertThrows(ServiceException.class,
                () -> draftService.confirm(1L, id, List.of(1), 99));
        assertEquals(BizErrorCode.VERSION_CONFLICT, stale.getCode());
        assertEquals(1, scriptService.getByCaseId(caseId).version(), "陈旧期望版本脚本不动");
        assertEquals(1, draftService.find(id).status(), "版本冲突后草稿仍待审可重试");
        AppautoRecordDraftView done = draftService.confirm(1L, id, List.of(2, 3), 1);
        ScriptInfo script = scriptService.getByCaseId(caseId);
        assertEquals(2, script.version());
        assertEquals(done.scriptVersion(), script.version());
        assertEquals(3, stepCount(script.actionJson()), "原 1 步+勾选 2 步=尾追合并 3 步");
        assertTrue(script.actionJson().contains("app.launch"), "既有步零丢失");
    }


    @Test
    void processedDraftRejectsFurtherActions() {
        enableRecordGate();
        long id = seedDraft();
        draftService.confirm(1L, id, List.of(1), null);
        ServiceException again = assertThrows(ServiceException.class,
                () -> draftService.confirm(1L, id, List.of(2), null));
        assertTrue(again.getMessage().contains("已处理") || again.getMessage().contains("已入库"));
        ServiceException rejectAfterAdopt = assertThrows(ServiceException.class,
                () -> draftService.reject(1L, id));
        assertTrue(rejectAfterAdopt.getMessage().contains("不可重复驳回"));

        long id2 = seedDraft();
        draftService.reject(1L, id2);
        assertEquals(3, draftService.find(id2).status());
        ServiceException confirmAfterReject = assertThrows(ServiceException.class,
                () -> draftService.confirm(1L, id2, List.of(1), null));
        assertTrue(confirmAfterReject.getMessage().contains("已驳回"));
        assertEquals(2, countDrafts());
    }


    @Test
    void listAndFindAndCheckedSelectionGuards() {
        enableRecordGate();
        long pending = seedDraft();
        long rejected = seedDraft();
        draftService.reject(1L, rejected);
        List<AppautoRecordDraftView> pendingOnly = draftService.list(caseId, 1, 50);
        assertTrue(pendingOnly.stream().anyMatch(v -> v.id() == pending));
        assertTrue(pendingOnly.stream().noneMatch(v -> v.id() == rejected), "状态过滤须精确");
        assertEquals(2, draftService.list(caseId, null, 50).size());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> draftService.find(999_999_999L));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode());

        ServiceException emptySelection = assertThrows(ServiceException.class,
                () -> draftService.confirm(1L, pending, List.of(), null));
        assertTrue(emptySelection.getMessage().contains("勾选"));

        ServiceException ghostStep = assertThrows(ServiceException.class,
                () -> draftService.confirm(1L, pending, List.of(9), null));
        assertTrue(ghostStep.getMessage().contains("9"), "越界勾选步序须点名");
        assertNull(scriptService.getByCaseId(caseId));
    }

    private int countDrafts() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_record_draft WHERE project_id = ?",
                Integer.class, projectId).intValue();
    }

    
    private int stepCount(String actionJson) {
        try {
            return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(actionJson).path("steps").size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
