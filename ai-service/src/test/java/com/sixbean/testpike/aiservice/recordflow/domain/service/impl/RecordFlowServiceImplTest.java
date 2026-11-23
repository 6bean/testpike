package com.sixbean.testpike.aiservice.recordflow.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.recordflow.api.dto.RecordFlowRequest;
import com.sixbean.testpike.aiservice.recordflow.domain.service.RecordFlowService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest
class RecordFlowServiceImplTest {

    
    private static final long PROJECT_BASE = 989_300_000L;

    @Autowired
    private RecordFlowService recordFlowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CoreGateway coreGateway;

    @MockitoBean
    private LlmFallbackChain llmFallbackChain;

    @MockitoBean
    private ProjectLlmRouter projectLlmRouter;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM tp_ai_call_log WHERE project_id >= ? AND project_id < ?",
                PROJECT_BASE, PROJECT_BASE + 1000L);
    }

    private void switchOn() {
        when(coreGateway.getSysConfig("ai.appauto.record-to-flow")).thenReturn("1");
        when(projectLlmRouter.route(anyLong()))
                .thenReturn(new LlmEndpointConfig("test-provider", "recordflow-test", "k", "http://x", "config"));
    }

    private RecordFlowRequest request(long projectId) {
        return new RecordFlowRequest(projectId, "MOBILEWRIGHT", "scrcpy",
                List.of(Map.of("kind", "tap", "ts", 1200, "detail", Map.of("x", 540, "y", 1200)),
                        Map.of("kind", "input", "text", "admin")),
                List.of("launch", "tap", "input", "assertVisible"));
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "recordflow-test",
                800, 300, 1200L),
                new LlmEndpointConfig("test-provider", "recordflow-test", "k", "http://x", "config"));
    }

    
    @Test
    void 关态裸打internal口_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.appauto.record-to-flow")).thenReturn("0");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 参数人话闸_零外呼() {
        switchOn();
        RecordFlowRequest noProject = new RecordFlowRequest(null, "MOBILEWRIGHT", "scrcpy",
                List.of(Map.of("kind", "tap")), List.of("tap"));
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(noProject));
        assertTrue(ex1.getMessage().contains("项目号"), ex1.getMessage());

        RecordFlowRequest noEvents = new RecordFlowRequest(PROJECT_BASE + 2, "MOBILEWRIGHT", "scrcpy",
                List.of(), List.of("tap"));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(noEvents));
        assertTrue(ex2.getMessage().contains("事件"), ex2.getMessage());

        RecordFlowRequest noVerbs = new RecordFlowRequest(PROJECT_BASE + 3, "MOBILEWRIGHT", "scrcpy",
                List.of(Map.of("kind", "tap")), List.of());
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(noVerbs));
        assertTrue(ex3.getMessage().contains("动词"), ex3.getMessage());

        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 正常链_解析回传且留痕一行() {
        switchOn();
        long projectId = PROJECT_BASE + 4;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        {"steps":[
                          {"action":"app.tap","target":{"x":540,"y":1200}},
                          {"action":"app.input","value":"admin","retry":2},
                          {"action":"app.assertVisible","target":{"text":"首页"}}
                        ]}"""));

        Map<String, Object> draft = recordFlowService.generate(request(projectId));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(3, steps.size());
        assertEquals("app.tap", steps.get(0).get("action"));
        assertEquals(2, ((Number) steps.get(1).get("retry")).intValue());
        assertTrue(steps.get(0).get("target") instanceof Map<?, ?>);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        String promptText = captor.getValue().messages().toString();
        assertTrue(promptText.contains("scrcpy"), "prompt 应含采集档位留痕");
        assertTrue(promptText.contains("assertVisible"), "prompt 应含 core 裁剪下传的动词清单");
        assertTrue(promptText.contains("tap"), "prompt 应含录制事件序列");

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '录制转流程反推'",
                Integer.class, projectId);
        assertEquals(1, logged);
    }

    
    @Test
    void 围栏包裹_容错剥壳解析() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        ```json
                        {"steps":[{"action":"app.back"}]}
                        ```"""));

        Map<String, Object> draft = recordFlowService.generate(request(PROJECT_BASE + 5));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(1, steps.size());
        assertEquals("app.back", steps.get(0).get("action"));
    }

    
    @Test
    void 结构违例_格式错误人话拒() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉我做不到"));
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex1.getCode().intValue());

        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"steps\":{\"action\":\"app.tap\"}}"));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> recordFlowService.generate(request(PROJECT_BASE + 7)));
        assertTrue(ex2.getMessage().contains("steps"), ex2.getMessage());
    }

    
    @Test
    void 越基线应答_原样回传不做第二套判定() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        {"steps":[{"action":"app.drag"},{"action":"click"}]}"""));

        Map<String, Object> draft = recordFlowService.generate(request(PROJECT_BASE + 8));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(2, steps.size());
        assertEquals("app.drag", steps.get(0).get("action"));
        assertEquals("click", steps.get(1).get("action"));
    }
}
