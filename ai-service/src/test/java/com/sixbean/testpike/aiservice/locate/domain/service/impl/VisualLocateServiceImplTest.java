package com.sixbean.testpike.aiservice.locate.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.locate.api.dto.VisualLocateRequest;
import com.sixbean.testpike.aiservice.locate.domain.service.VisualLocateService;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest
class VisualLocateServiceImplTest {

    
    private static final long PROJECT_BASE = 989_100_000L;

    @Autowired
    private VisualLocateService visualLocateService;

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

    private void switchesOn() {
        when(coreGateway.getSysConfig("ai.vision.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.vision.appauto-locate")).thenReturn("1");
    }

    private VisualLocateRequest request(long projectId) {
        return new VisualLocateRequest(902L, projectId, "aGk=", "image/png",
                List.of(Map.of("label", "提交", "x", 100, "y", 200, "w", 50, "h", 40)),
                Map.of("action", "app.tap", "selector", Map.of("testTag", "submit")),
                "app.tap", "tap");
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "vision-test",
                120, 30, 800L),
                new LlmEndpointConfig("test-provider", "vision-test", "k", "http://x", "config"));
    }

    
    @Test
    void 总闸关_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.vision.enabled")).thenReturn("0");
        when(coreGateway.getSysConfig("ai.vision.appauto-locate")).thenReturn("1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 分开关关_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.vision.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.vision.appauto-locate")).thenReturn("0");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(PROJECT_BASE + 2)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 模型键空_人话拒零外呼() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(PROJECT_BASE + 3)));
        assertTrue(ex.getMessage().contains("视觉模型未配置"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 截图缺失_参数人话拒() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");

        VisualLocateRequest noShot = new VisualLocateRequest(902L, PROJECT_BASE + 4, null,
                "image/png", List.of(), Map.of("action", "app.tap"), "app.tap", "tap");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(noShot));
        assertTrue(ex.getMessage().contains("截图"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 正常链_解析回传且留痕一行() {
        switchesOn();
        long projectId = PROJECT_BASE + 5;
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("```json\n{\"x\":320,\"y\":540,\"label\":\"提交按钮\",\"confidence\":0.91}\n```"));

        Map<String, Object> located = visualLocateService.locate(request(projectId));

        assertEquals(320, ((Number) located.get("x")).intValue());
        assertEquals(540, ((Number) located.get("y")).intValue());
        assertEquals("提交按钮", located.get("label"));
        assertEquals(0.91, ((Number) located.get("confidence")).doubleValue(), 1e-9);

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '视觉定位兜底'",
                Integer.class, projectId);
        assertEquals(1, logged);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        List<ChatMessage> messages = captor.getValue().messages();
        String visionUrl = messages.get(messages.size() - 1).parts().get(1).imageUrl().url();
        assertTrue(visionUrl.startsWith("data:image/png;base64,"), visionUrl);
        assertNotNull(captor.getValue().model());
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(messages.get(0).content()),
                "缺省开+无发布行=内置契约+不变量恰一次: " + messages.get(0).content());
    }

    
    @Test
    void 模型输出不可解析_格式错误拒() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉，我看不清这张截图"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    
    @Test
    void 坐标非数字_格式错误拒() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"x\":\"左上角\",\"y\":null,\"label\":\"按钮\",\"confidence\":0.8}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> visualLocateService.locate(request(PROJECT_BASE + 7)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    
    @Test
    void 内核接线_已发布模板行替换内置契约且不变量恰一次() {
        switchesOn();
        long projectId = PROJECT_BASE + 8;
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        String published = "你是视觉定位助手（模板页定制口径）。按目标描述与截图给出点击点位，"
                + "只输出 JSON：{\"x\":数字,\"y\":数字,\"label\":\"说明\",\"confidence\":0到1的小数}";
        when(coreGateway.promptTemplateLatestByStatus(
                "locate-directives", "visual-locate", 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(9L, "locate-directives",
                        "visual-locate", 1, published, 2));
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"x\":10,\"y\":20,\"label\":\"提交\",\"confidence\":0.5}"));

        visualLocateService.locate(request(projectId));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        String head = captor.getValue().messages().get(0).content();
        assertTrue(head.contains(published), "已发布行=头正文: " + head);
        assertTrue(!head.contains("你是移动端测试执行的视觉定位助手"), "模板在位=内置契约不叠加: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head), "内核不变量恰一次: " + head);
    }

    
    @Test
    void 内核接线_治理键关_零模板读取回退内置契约() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(coreGateway.getSysConfig("ai.vision.locate-directives.enabled")).thenReturn("0");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"x\":10,\"y\":20,\"label\":\"提交\",\"confidence\":0.5}"));

        visualLocateService.locate(request(PROJECT_BASE + 9));

        verify(coreGateway, org.mockito.Mockito.never())
                .promptTemplateLatestByStatus(any(), any(), anyInt());
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        String head = captor.getValue().messages().get(0).content();
        assertTrue(head.contains("你是移动端测试执行的视觉定位助手"), "关态=内置契约头: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head), "内核不变量恰一次: " + head);
    }

    
    @Test
    void 内核接线_模板读异常_降级内置契约应答不炸() {
        switchesOn();
        long projectId = PROJECT_BASE + 10;
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(coreGateway.promptTemplateLatestByStatus(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("core 模板端点不可达"));
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"x\":320,\"y\":540,\"label\":\"提交按钮\",\"confidence\":0.9}"));

        Map<String, Object> located = visualLocateService.locate(request(projectId));

        assertEquals(320, ((Number) located.get("x")).intValue());
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        String head = captor.getValue().messages().get(0).content();
        assertTrue(head.contains("你是移动端测试执行的视觉定位助手"), "降级=内置契约头: " + head);
        assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head), "降级路径不变量仍恰一次: " + head);
    }

}
