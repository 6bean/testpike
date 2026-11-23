package com.sixbean.testpike.aiservice.repair.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.repair.domain.service.ScriptRepairLoopService;
import com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest
class ScriptRepairLoopServiceImplTest {

    
    private static final long PROJECT_BASE = 990_000_000L;

    private static final String VALID_ACTION_JSON = """
            {"version":"v2","caseId":"TC-RPR-001","endType":"web",
             "setup":[{"action":"goto","target":{"url":"http://localhost:8899/login"}}],
             "steps":[{"action":"click","target":{"css":"#login-btn","chain":["css"]}},
                      {"action":"expect","target":{"text":"登录成功"}}],
             "env":{"ENV":"rtl-test"}}
            """;

    @Autowired
    private ScriptRepairLoopService repairLoopService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @MockitoBean
    private LlmFallbackChain llmFallbackChain;

    @BeforeEach
    void zeroWaitSeams() {
        ((ScriptRepairLoopServiceImpl) repairLoopService).configureWaitsForTest(1, 0L, 1, 0L);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_ai_call_log WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
    }

    private ExecFailedEventMessage message(long projectId, long taskId) {
        return new ExecFailedEventMessage("EXEC_TASK_FAILED", taskId * 10, projectId, null,
                null, null, null, null, null, 1_700_000_000_000L, null,
                taskId, 2, "元素未找到：#submit-btn", null);
    }

    private RepairTaskView taskView(long projectId, long caseId, int status, Integer aiClass,
                                    String errorDetail) {
        return new RepairTaskView(projectId, caseId, status, "v2", VALID_ACTION_JSON,
                aiClass, errorDetail);
    }

    private void enableGate(long projectId) {
        when(coreGateway.getSysConfig("ai.repair.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.repair.rounds")).thenReturn("3");
        when(coreGateway.getSysConfig("ai.repair.model")).thenReturn("glm-repair-test");
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "glm-repair-test",
                200, 60, 900L),
                new LlmEndpointConfig("test-provider", "glm-repair-test", "k", "http://x", "config"));
    }

    
    @Test
    void 开关关_零落行零外呼且关态留痕() {
        long projectId = PROJECT_BASE + 1;
        when(coreGateway.getSysConfig("ai.repair.enabled")).thenReturn("0");

        repairLoopService.repair(message(projectId, 950_001L));

        verifyNoInteractions(llmFallbackChain);
        verify(coreGateway, never()).recordScriptRepair(anyLong(), anyLong(), any(), any(),
                any(), anyInt(), any());
        ArgumentCaptor<LlmAuditRequest> captor = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(captor.capture());
        assertEquals("REPAIR_GATE_SKIP", captor.getValue().action(),
                "关态留痕动作名（CLASSIFY_GATE_SKIP 同基径）");
        assertEquals(projectId, captor.getValue().projectId());
    }

    
    @Test
    void 归因未完成_落行3不进环() {
        long projectId = PROJECT_BASE + 2;
        long taskId = 950_002L;
        enableGate(projectId);
        when(coreGateway.getRepairTaskView(taskId))
                .thenReturn(taskView(projectId, taskId * 10, 4, null, "元素未找到：#submit-btn"));

        repairLoopService.repair(message(projectId, taskId));

        verify(coreGateway).recordScriptRepair(eq(projectId), eq(taskId * 10), eq(taskId), eq(null),
                eq(null), eq(3), contains("归因未完成"));
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 产品缺陷类_落行3不进环() {
        long projectId = PROJECT_BASE + 3;
        long taskId = 950_003L;
        enableGate(projectId);
        when(coreGateway.getRepairTaskView(taskId))
                .thenReturn(taskView(projectId, taskId * 10, 4, 1, "接口 500"));

        repairLoopService.repair(message(projectId, taskId));

        verify(coreGateway).recordScriptRepair(eq(projectId), eq(taskId * 10), eq(taskId), eq(null),
                eq(null), eq(3), contains("产品缺陷"));
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 轮次达上限_落行3转人工() {
        long projectId = PROJECT_BASE + 4;
        long taskId = 950_004L;
        long caseId = taskId * 10;
        enableGate(projectId);
        when(coreGateway.getRepairTaskView(taskId))
                .thenReturn(taskView(projectId, caseId, 4, 2, "元素未找到：#submit-btn"));
        when(coreGateway.repairRounds(caseId)).thenReturn(3L);

        repairLoopService.repair(message(projectId, taskId));

        verify(coreGateway).recordScriptRepair(eq(projectId), eq(caseId), eq(taskId), eq(null),
                eq(null), eq(3), contains("轮次达上限"));
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 提案非法_落行3零试运行() {
        long projectId = PROJECT_BASE + 5;
        long taskId = 950_005L;
        long caseId = taskId * 10;
        enableGate(projectId);
        when(coreGateway.getRepairTaskView(taskId))
                .thenReturn(taskView(projectId, caseId, 4, 2, "元素未找到：#submit-btn"));
        when(coreGateway.repairRounds(caseId)).thenReturn(0L);
        when(llmFallbackChain.chatHandle(any(), eq(projectId), eq(null)))
                .thenReturn(handle("这显然不是一段动作 JSON"));

        repairLoopService.repair(message(projectId, taskId));

        verify(coreGateway).recordScriptRepair(eq(projectId), eq(caseId), eq(taskId), eq(null),
                eq(null), eq(3), contains("提案非法"));
        verify(coreGateway, never()).createRepairTrialTask(anyLong(), any(), any());
        assertEquals(1, callLogCount(projectId), "LLM 已调用=留痕必须落一行");
    }

    
    @Test
    void 修复通过_落版行1且通知测试负责人() {
        long projectId = PROJECT_BASE + 6;
        long taskId = 950_006L;
        long caseId = taskId * 10;
        long trialTaskId = 960_006L;
        enableGate(projectId);
        when(coreGateway.getRepairTaskView(taskId))
                .thenReturn(taskView(projectId, caseId, 4, 2, "元素未找到：#submit-btn"));
        when(coreGateway.repairRounds(caseId)).thenReturn(1L);
        when(llmFallbackChain.chatHandle(any(), eq(projectId), eq(null)))
                .thenReturn(handle(VALID_ACTION_JSON));
        when(coreGateway.createRepairTrialTask(eq(taskId), eq("v2"), anyString()))
                .thenReturn(trialTaskId);
        when(coreGateway.getRepairTaskView(trialTaskId))
                .thenReturn(taskView(projectId, caseId, 3, null, null));
        when(coreGateway.getProject(projectId))
                .thenReturn(new CoreGateway.ProjectBrief(projectId, "rpr", "zh-CN", 0, 0, 777L));

        repairLoopService.repair(message(projectId, taskId));

        verify(coreGateway).saveRepairedScript(eq(caseId), eq("v2"), anyString());
        verify(coreGateway).recordScriptRepair(eq(projectId), eq(caseId), eq(taskId), eq(trialTaskId),
                anyString(), eq(1), eq(null));
        verify(coreGateway).createNotification(eq(777L), eq(projectId), eq("AI_REPAIRED"),
                anyString(), anyString(), eq("ai-repaired:" + trialTaskId));
        assertEquals(1, callLogCount(projectId), "提案 LLM 调用留痕真库对账");
    }

    
    @Test
    void 试运行未过_落行2原脚本零触碰() {
        long projectId = PROJECT_BASE + 7;
        long taskId = 950_007L;
        long caseId = taskId * 10;
        long trialTaskId = 960_007L;
        enableGate(projectId);
        when(coreGateway.getRepairTaskView(taskId))
                .thenReturn(taskView(projectId, caseId, 4, 2, "元素未找到：#submit-btn"));
        when(coreGateway.repairRounds(caseId)).thenReturn(0L);
        when(llmFallbackChain.chatHandle(any(), eq(projectId), eq(null)))
                .thenReturn(handle(VALID_ACTION_JSON));
        when(coreGateway.createRepairTrialTask(eq(taskId), eq("v2"), anyString()))
                .thenReturn(trialTaskId);
        when(coreGateway.getRepairTaskView(trialTaskId))
                .thenReturn(taskView(projectId, caseId, 4, null, "试运行仍失败"));

        repairLoopService.repair(message(projectId, taskId));

        verify(coreGateway, never()).saveRepairedScript(anyLong(), any(), any());
        verify(coreGateway).recordScriptRepair(eq(projectId), eq(caseId), eq(taskId), eq(trialTaskId),
                anyString(), eq(2), contains("未过"));
    }

    private String contains(String fragment) {
        return org.mockito.ArgumentMatchers.argThat(value ->
                value != null && value.contains(fragment));
    }

    private int callLogCount(long projectId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '脚本修复'",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }
}
