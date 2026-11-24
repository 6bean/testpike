package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoRecordFlowView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceRecordFlowClient;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest(classes = CoreApplication.class)
class AppautoRecordFlowServiceTest {

    private static final String RECORD_KEY = AppautoConfigReader.KEY_RECORD_TO_FLOW;

    @Autowired
    private AppautoRecordFlowService recordFlowService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private AiServiceRecordFlowClient aiServiceRecordFlowClient;

    private long projectId;
    private long caseId;
    private long taskId;

    @BeforeEach
    void setUp() {
        String code = "rf" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "录制反推测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        caseId = caseService.create(1L, new CaseCreateInput(projectId, "录制反推用例", "前置",
                "预期", 1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "app", "MOBILEWRIGHT", null)).id();
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, ?, 4, 6, '{"kind":"app-auto-run","steps":[]}')
                """, projectId, caseId);
        taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE project_id = ? AND case_id = ?",
                Long.class, projectId, caseId).longValue();
        cleanDrafts();
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(RECORD_KEY);
        cleanDrafts();
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanDrafts() {
        jdbcTemplate.update("DELETE FROM tp_appauto_record_draft WHERE project_id = ?", projectId);
    }

    private long draftRowCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_record_draft WHERE project_id = ?",
                Long.class, projectId);
        return n == null ? 0L : n;
    }

    private AppautoRecordFlowRequest request(String engineCode, String eventSource,
                                             List<Map<String, Object>> events) {
        return new AppautoRecordFlowRequest(taskId, engineCode, eventSource, "登录录制", events);
    }

    @Test
    void switchOffRejectsHumanWordsAndNeverCallsModel() {
        configCacheHolder.put(RECORD_KEY, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        ServiceException ex = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(
                        request("MOBILEWRIGHT", "scrcpy", List.of(Map.of("kind", "tap")))));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("未开启：请在「系统管理 → 系统配置」搜索") && ex.getMessage().contains("ai.appauto.record-to-flow"), "关态文案须人话说明开关未开：" + ex.getMessage());
        verifyNoInteractions(aiServiceRecordFlowClient);
        assertEquals(0, draftRowCount());
    }

    @Test
    void parameterGatesRejectBeforeAnyModelCall() {
        configCacheHolder.put(RECORD_KEY, "1");
        List<AppautoRecordFlowRequest> bad = List.of(
                new AppautoRecordFlowRequest(null, "MOBILEWRIGHT", "scrcpy", null,
                        List.of(Map.of("kind", "tap"))),
                request(null, "scrcpy", List.of(Map.of("kind", "tap"))),
                request("MOBILEWRIGHT", null, List.of(Map.of("kind", "tap"))),
                request("MOBILEWRIGHT", "scrcpy", List.of()));
        for (AppautoRecordFlowRequest r : bad) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> recordFlowService.generate(r));
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), ex.getMessage());
        }
        verifyNoInteractions(aiServiceRecordFlowClient);
    }

    @Test
    void unknownEngineRejectsHuman404() {
        configCacheHolder.put(RECORD_KEY, "1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(
                        request("NO_SUCH_ENGINE", "scrcpy", List.of(Map.of("kind", "tap")))));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(aiServiceRecordFlowClient);
    }

    @Test
    void disabledEngineRejectedWithHumanWords() {
        configCacheHolder.put(RECORD_KEY, "1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(
                        request("HYPIUM", "scrcpy", List.of(Map.of("kind", "tap")))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("下架"), "须点名引擎已下架：" + ex.getMessage());
        verifyNoInteractions(aiServiceRecordFlowClient);
    }

    @Test
    void normalChainDownloadsTrimmedVerbsAndRegistersDraftWithValues() {
        configCacheHolder.put(RECORD_KEY, "1");
        List<Map<String, Object>> events = List.of(
                Map.of("kind", "tap", "target", Map.of("text", "登录")),
                Map.of("kind", "key", "detail", "input", "text", "admin"));
        when(aiServiceRecordFlowClient.generate(any())).thenReturn(List.of(
                Map.of("action", "app.tap", "target", Map.of("text", "登录")),
                Map.of("action", "app.input", "target", Map.of("resourceId", "com.demo:id/user"),
                        "value", "admin")));
        AppautoRecordFlowView view = recordFlowService.generate(
                request("mobilewright", "scrcpy", events));

        ArgumentCaptor<AppautoRecordFlowLlmRequest> captor =
                ArgumentCaptor.forClass(AppautoRecordFlowLlmRequest.class);
        verify(aiServiceRecordFlowClient).generate(captor.capture());
        AppautoRecordFlowLlmRequest down = captor.getValue();
        assertEquals(projectId, down.projectId(), "projectId 必须由 taskId 反查权威补入");
        assertEquals("MOBILEWRIGHT", down.engineCode(), "引擎 code 归一大写下传");
        assertEquals(events, down.events(), "事件序列原样下传禁二次加工");
        assertEquals(List.of(
                "launch", "close", "tap", "longPress", "doubleTap", "input", "clearInput",
                "swipe", "scroll", "back", "screenshot", "assertVisible", "assertNotVisible",
                "assertText", "waitVisible", "extractText",
                "deepLink", "openUrl", "rotation", "home", "clearData", "grantPermission",
                "wakeUnlock", "networkProfile", "install", "uninstall", "verifyVersion",
                "perf", "launchPerf"),
                down.allowedVerbs(),
                "允许动词集=IR 基线∩引擎位图（MOBILEWRIGHT 尽力位两枚 false 在案，裁剪单点 core）");

        assertEquals(2, view.stepCount());
        assertTrue(view.draftId() > 0, "登记后必须返回草稿号");
        assertEquals(1, draftRowCount());
        String items = jdbcTemplate.queryForObject(
                "SELECT items::text FROM tp_appauto_record_draft WHERE project_id = ?",
                String.class, projectId);
        assertNotNull(items);
        assertTrue(items.replaceAll("\\s", "").contains("\"value\":\"admin\""),
                "input 步 value 必须穿透到草稿 items（丢值=入库脚本不可执行）：" + items);
        int status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_appauto_record_draft WHERE id = ?",
                Integer.class, view.draftId());
        assertEquals(1, status, "草稿只进待审（零自动入库红线）");
    }

    @Test
    void missingTargetStepGetsEmptyLocatorAndRegisters() {
        configCacheHolder.put(RECORD_KEY, "1");
        when(aiServiceRecordFlowClient.generate(any())).thenReturn(List.of(
                Map.of("action", "app.back")));
        AppautoRecordFlowView view = recordFlowService.generate(
                request("MOBILEWRIGHT", "native", List.of(Map.of("kind", "key", "detail", "back"))));
        assertEquals(1, view.stepCount());
        assertEquals(1, draftRowCount());
    }

    @Test
    void emptyStepsAnswerRejectedWithZeroDraftRows() {
        configCacheHolder.put(RECORD_KEY, "1");
        when(aiServiceRecordFlowClient.generate(any())).thenReturn(List.of());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(
                        request("MOBILEWRIGHT", "native", List.of(Map.of("kind", "tap")))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("步骤"), ex.getMessage());
        assertEquals(0, draftRowCount());
    }

    @Test
    void outOfBaselineVerbRejectedFailClosed() {
        configCacheHolder.put(RECORD_KEY, "1");
        when(aiServiceRecordFlowClient.generate(any())).thenReturn(List.of(
                Map.of("action", "app.drag", "target", Map.of("x", 1))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(
                        request("MOBILEWRIGHT", "native", List.of(Map.of("kind", "swipe")))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("第 1 步"), "须点名违例步号：" + ex.getMessage());
        assertEquals(0, draftRowCount());
    }

    @Test
    void stringTargetRejectedByNameFailClosed() {
        configCacheHolder.put(RECORD_KEY, "1");
        when(aiServiceRecordFlowClient.generate(any())).thenReturn(List.of(
                Map.of("action", "app.tap", "target", "登录按钮")));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(
                        request("MOBILEWRIGHT", "native", List.of(Map.of("kind", "tap")))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("定位器对象"), "须点名 target 对象形要求：" + ex.getMessage());
        assertEquals(0, draftRowCount());
    }
}
