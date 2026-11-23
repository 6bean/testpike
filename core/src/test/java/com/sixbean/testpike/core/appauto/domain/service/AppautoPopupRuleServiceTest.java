package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPopupRuleView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.MonitorFrameBuffer;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServicePopupLearnClient;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest(classes = CoreApplication.class)
class AppautoPopupRuleServiceTest {

    @Autowired
    private AppautoPopupRuleService popupRuleService;
    @Autowired
    private MonitorFrameBuffer frameBuffer;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private AiServicePopupLearnClient aiServicePopupLearnClient;

    private long projectId;
    private long taskId;

    @BeforeEach
    void setUp() {
        String code = "pr" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "弹窗规则测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, 0, 4, 6, '{"kind":"app-auto-run","steps":[]}')
                """, projectId);
        taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, projectId).longValue();
        cleanTables();
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(AppautoConfigReader.KEY_POPUP_RULE_LEARN);
        cleanTables();
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_appauto_popup_rule WHERE project_id = ?", projectId);
    }

    private void seedFrames(String... sensitiveFlags) {
        List<Map<String, Object>> frames = new java.util.ArrayList<>();
        for (int i = 0; i < sensitiveFlags.length; i++) {
            frames.add(Map.of("taskId", taskId, "stepNo", i + 1,
                    "image", "iVBORw0KGgoAAAANSUhEUg==",
                    "sensitive", "1".equals(sensitiveFlags[i])));
        }
        frameBuffer.ingest(frames);
    }

    private Map<String, Object> ruleResponse() {
        return Map.of("rule", Map.of(
                "detect", Map.of("textContains", "检测到更新"),
                "dismiss", Map.of("action", "app.tap", "target", Map.of("text", "以后再说"))),
                "title", "更新弹窗");
    }


    @Test
    void migrationArtifactsInPlace() {
        Integer cols = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'tp_appauto_popup_rule'",
                Integer.class);
        assertNotNull(cols);
        assertTrue(cols >= 9, "V190 规则表列不齐");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_sys_config WHERE config_key = ? AND config_value = '1'",
                Integer.class, AppautoConfigReader.KEY_POPUP_RULE_LEARN).intValue(),
                "learn 键默认必须开（AI 功能出厂默认=开，2026-10-26 裁决翻值批）");
    }


    @Test
    void learnSwitchOffRejectsHumanWordsAndNeverCallsModel() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        seedFrames("0");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupRuleService.learn(taskId));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("未开启：请在「系统管理 → 系统配置」搜索") && ex.getMessage().contains("ai.appauto.popup-rule-learn"), "关态文案须人话：{" + ex.getMessage() + "}");
        verifyNoInteractions(aiServicePopupLearnClient);
        assertEquals(0, countDrafts());
    }


    @Test
    void learnOnProducesPendingDraftWithAuthoritativeProject() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        seedFrames("0", "0");
        when(aiServicePopupLearnClient.recognize(any())).thenReturn(ruleResponse());
        long id = popupRuleService.learn(taskId);
        AppautoPopupRuleView row = popupRuleService.find(id);
        assertEquals(1, row.status(), "自学产物必须待审态（零自动入库红线）");
        assertEquals(projectId, row.projectId(), "归属=任务行权威反查");
        assertEquals(taskId, row.sourceTaskId());
        assertTrue(row.rule().contains("检测到更新"), "规则交换 JSON 原样留痕");
        assertEquals("AI", row.source());
    }


    @Test
    void sensitiveFramesAreNeverModelInput() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        seedFrames("1", "1");
        assertThrows(ServiceException.class, () -> popupRuleService.learn(taskId));
        verifyNoInteractions(aiServicePopupLearnClient);
        assertEquals(0, countDrafts());
    }


    @Test
    void emptyFrameRingRejectsHumanWords() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupRuleService.learn(taskId));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(aiServicePopupLearnClient);
    }


    @Test
    void malformedAiResponseNeverProducesDraft() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        seedFrames("0");
        when(aiServicePopupLearnClient.recognize(any()))
                .thenReturn(Map.of("rule", Map.of("detect", Map.of("text", "x"))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupRuleService.learn(taskId));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertEquals(0, countDrafts());
    }

    @Test
    void aiResponseWithoutRuleNeverProducesDraft() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        seedFrames("0");
        when(aiServicePopupLearnClient.recognize(any())).thenReturn(Map.of());
        assertThrows(ServiceException.class, () -> popupRuleService.learn(taskId));
        assertEquals(0, countDrafts());
    }


    @Test
    void confirmIsTheOnlyIngestChannelAndRejectsReplay() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        seedFrames("0");
        when(aiServicePopupLearnClient.recognize(any())).thenReturn(ruleResponse());
        long id = popupRuleService.learn(taskId);
        assertEquals(2, popupRuleService.confirm(1L, id).status(), "人批准=唯一入库通道");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupRuleService.confirm(1L, id));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
    }

    @Test
    void rejectFlipsToRejectedState() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        seedFrames("0");
        when(aiServicePopupLearnClient.recognize(any())).thenReturn(ruleResponse());
        long id = popupRuleService.learn(taskId);
        popupRuleService.reject(1L, id);
        assertEquals(3, popupRuleService.find(id).status());
    }


    @Test
    void listFiltersByProjectAndStatus() {
        configCacheHolder.put(AppautoConfigReader.KEY_POPUP_RULE_LEARN, "1");
        seedFrames("0");
        when(aiServicePopupLearnClient.recognize(any())).thenReturn(ruleResponse());
        long id = popupRuleService.learn(taskId);
        assertEquals(1, popupRuleService.list(projectId, 1, 50).size());
        assertEquals(0, popupRuleService.list(projectId, 2, 50).size());
        popupRuleService.confirm(1L, id);
        assertEquals(1, popupRuleService.list(projectId, 2, 50).size());
    }

    @Test
    void findUnknownRejectsHuman404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupRuleService.find(999999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    private int countDrafts() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_popup_rule WHERE project_id = ?",
                Integer.class, projectId).intValue();
    }
}
