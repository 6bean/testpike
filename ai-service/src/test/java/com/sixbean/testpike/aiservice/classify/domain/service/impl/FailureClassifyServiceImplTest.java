package com.sixbean.testpike.aiservice.classify.domain.service.impl;

import com.sixbean.testpike.aiservice.classify.domain.service.FailureClassifyService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.ExecFailedEventMessage;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest
class FailureClassifyServiceImplTest {

    
    private static final long PROJECT_BASE = 989_000_000L;

    @Autowired
    private FailureClassifyService classifyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @MockitoBean
    private LlmFallbackChain llmFallbackChain;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_ai_call_log WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
    }

    private ExecFailedEventMessage message(long projectId, long taskId, String errorDetail) {
        return new ExecFailedEventMessage("EXEC_TASK_FAILED", taskId * 10, projectId, null,
                null, null, null, null, null, 1_700_000_000_000L, null,
                taskId, 2, errorDetail, null);
    }

    
    private ExecFailedEventMessage messageWithShot(long projectId, long taskId, String errorDetail,
                                                   String screenshotKey) {
        return new ExecFailedEventMessage("EXEC_TASK_FAILED", taskId * 10, projectId, null,
                null, null, null, null, null, 1_700_000_000_000L, null,
                taskId, 2, errorDetail, screenshotKey);
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "glm-4-test",
                120, 30, 800L),
                new LlmEndpointConfig("test-provider", "glm-4-test", "k", "http://x", "config"));
    }

    
    @Test
    void 开关关_零外呼且关态留痕() {
        long projectId = PROJECT_BASE + 1;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("0");

        classifyService.classify(message(projectId, 900_001L, "元素未找到"));

        verifyNoInteractions(llmFallbackChain);
        ArgumentCaptor<LlmAuditRequest> captor = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(captor.capture());
        LlmAuditRequest audit = captor.getValue();
        assertEquals("CLASSIFY_GATE_SKIP", audit.action(), "关态留痕动作名（KB_GATE_SKIP 同基径）");
        assertEquals(projectId, audit.projectId());
        assertTrue(String.valueOf(audit.objectId()).contains("exec-classify:task:900001"),
                "objectId 点名任务幂等段: " + audit.objectId());
    }

    
    @Test
    void 同类错误复用命中_直写回零LLM() {
        long projectId = PROJECT_BASE + 2;
        String detail = "断言失败：预期 200 实际 500";
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of(
                Map.of("error_detail", detail, "ai_failure_class", 3)));

        classifyService.classify(message(projectId, 900_002L, detail));

        verify(coreGateway).aiClassify(eq(900_002L), eq(3), any(String.class));
        verifyNoInteractions(llmFallbackChain);
        assertEquals(0, callLogCount(projectId), "复用零外呼=零 call_log");
    }

    
    @Test
    void 正常归因链_写回且调用留痕落库() {
        long projectId = PROJECT_BASE + 3;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("```json\n{\"classification\":1,\"note\":\"登录按钮定位器随页面改版失效\"}\n```"));

        classifyService.classify(message(projectId, 900_003L, "元素未找到：#login-btn"));

        verify(coreGateway).aiClassify(900_003L, 1, "登录按钮定位器随页面改版失效");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT function, model, provider, prompt_tokens FROM tp_ai_call_log WHERE project_id = ?",
                projectId);
        assertEquals(1, rows.size(), "归因成功=tp_ai_call_log 恰一行（TC-C3-01 留痕链路）");
        assertEquals("失败归因", rows.get(0).get("function"));
        assertEquals("glm-4-test", rows.get(0).get("model"));
        assertEquals(120, ((Number) rows.get(0).get("prompt_tokens")).intValue());
    }

    
    @Test
    void 值域外输出_零写回() {
        long projectId = PROJECT_BASE + 4;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("我不知道该归为哪类"));

        classifyService.classify(message(projectId, 900_004L, "超时"));

        verify(coreGateway, org.mockito.Mockito.never())
                .aiClassify(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    
    @Test
    void 模型异常_上抛零写回零留痕() {
        long projectId = PROJECT_BASE + 5;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenThrow(new ServiceException("LLM 供应商全链失败", BizErrorCode.AI_PROVIDER_ERROR));

        assertThrows(ServiceException.class,
                () -> classifyService.classify(message(projectId, 900_005L, "元素未找到")));
        assertEquals(0, callLogCount(projectId), "调用失败不留 call_log 行");
    }

    
    @Test
    void 视觉开关开_归因请求带图文段() {
        long projectId = PROJECT_BASE + 6;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(coreGateway.getProject(projectId))
                .thenReturn(new CoreGateway.ProjectBrief(projectId, "VIS", "zh-CN", 1, 1));
        when(coreGateway.screenshotUrl(900_006L, "exec/1/step-3.png"))
                .thenReturn("http://s3:9000/testpike/exec/1/step-3.png?X-Amz-Signature=s");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("{\"classification\":1,\"note\":\"页面报错\"}"));

        classifyService.classify(messageWithShot(projectId, 900_006L, "元素未找到", "exec/1/step-3.png"));

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), eq(projectId), any());
        var userMsg = captor.getValue().messages().get(1);
        assertNotNull(userMsg.parts(), "键开=user 段应为多模态 parts");
        assertTrue(userMsg.parts().stream().anyMatch(p -> "image_url".equals(p.type())
                        && p.imageUrl().url().contains("step-3.png")),
                "图文段应含末步截图预签名 URL: " + userMsg.parts());
    }

    
    @Test
    void 视觉开关关_纯文本且零签发() {
        long projectId = PROJECT_BASE + 7;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(coreGateway.getProject(projectId))
                .thenReturn(new CoreGateway.ProjectBrief(projectId, "VIS", "zh-CN", 1, 0));
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("{\"classification\":2,\"note\":\"定位失效\"}"));

        classifyService.classify(messageWithShot(projectId, 900_007L, "元素未找到", "exec/1/step-3.png"));

        verify(coreGateway, org.mockito.Mockito.never()).screenshotUrl(anyLong(), any());
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), eq(projectId), any());
        assertEquals(null, captor.getValue().messages().get(1).parts(), "关态=纯文本 content");
    }

    
    @Test
    void 签发异常_降级纯文本主链不炸() {
        long projectId = PROJECT_BASE + 8;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(coreGateway.getProject(projectId))
                .thenReturn(new CoreGateway.ProjectBrief(projectId, "VIS", "zh-CN", 1, 1));
        when(coreGateway.screenshotUrl(anyLong(), any()))
                .thenThrow(new ServiceException("截图签发失败", BizErrorCode.INTERNAL_ERROR));
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("{\"classification\":3,\"note\":\"环境不可达\"}"));

        classifyService.classify(messageWithShot(projectId, 900_008L, "超时", "exec/1/step-3.png"));

        verify(coreGateway).aiClassify(900_008L, 3, "环境不可达");
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), eq(projectId), any());
        assertEquals(null, captor.getValue().messages().get(1).parts(), "签发失败回退纯文本");
    }

    
    @Test
    void 内核接线_已发布模板行替换内置指令且不变量恰一次() {
        long projectId = PROJECT_BASE + 9;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        String published = "你是归因助手（模板页定制口径）。按 1=产品缺陷 2=AI定位错误 3=环境问题 归类，"
                + "只输出 JSON：{\"classification\":数字,\"note\":\"一句话中文说明\"}";
        when(coreGateway.promptTemplateLatestByStatus(
                "classify-directives", "failure-classify", 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(7L, "classify-directives",
                        "failure-classify", 1, published, 2));
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("{\"classification\":1,\"note\":\"页面改版\"}"));

        classifyService.classify(message(projectId, 900_009L, "元素未找到"));

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), eq(projectId), any());
        String head = captor.getValue().messages().get(0).content();
        assertTrue(head.contains(published), "已发布行=头正文: " + head);
        assertTrue(!head.contains("你是测试执行失败归因助手"), "模板在位=内置契约不叠加: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head), "内核不变量恰一次: " + head);
    }

    
    @Test
    void 内核接线_治理键关_零模板读取回退内置契约() {
        long projectId = PROJECT_BASE + 10;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(coreGateway.getSysConfig("ai.classify.directives.enabled")).thenReturn("0");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("{\"classification\":2,\"note\":\"定位器失效\"}"));

        classifyService.classify(message(projectId, 900_010L, "元素未找到"));

        verify(coreGateway, org.mockito.Mockito.never())
                .promptTemplateLatestByStatus(any(), any(), anyInt());
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), eq(projectId), any());
        String head = captor.getValue().messages().get(0).content();
        assertTrue(head.contains("你是测试执行失败归因助手"), "关态=内置契约头: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head), "内核不变量恰一次: " + head);
    }

    
    @Test
    void 内核接线_模板读异常_降级内置契约主链不炸() {
        long projectId = PROJECT_BASE + 11;
        when(coreGateway.getSysConfig("ai.classify.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.classify.model")).thenReturn("glm-4-test");
        when(coreGateway.listRecentClassify(anyLong())).thenReturn(List.of());
        when(coreGateway.promptTemplateLatestByStatus(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("core 模板端点不可达"));
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), eq(projectId), any()))
                .thenReturn(handle("{\"classification\":3,\"note\":\"环境不可达\"}"));

        classifyService.classify(message(projectId, 900_011L, "超时"));

        verify(coreGateway).aiClassify(900_011L, 3, "环境不可达");
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), eq(projectId), any());
        String head = captor.getValue().messages().get(0).content();
        assertTrue(head.contains("你是测试执行失败归因助手"), "降级=内置契约头: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head), "降级路径不变量仍恰一次: " + head);
    }

    private int callLogCount(long projectId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ?", Integer.class, projectId);
        return count == null ? 0 : count;
    }
}
