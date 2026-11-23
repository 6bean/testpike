package com.sixbean.testpike.aiservice.nlstep.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.nlstep.api.dto.NlStepRequest;
import com.sixbean.testpike.aiservice.nlstep.domain.service.NlStepService;
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
class NlStepServiceImplTest {

    
    private static final long PROJECT_BASE = 989_200_000L;

    @Autowired
    private NlStepService nlStepService;

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
        when(coreGateway.getSysConfig("ai.appauto.nl-step")).thenReturn("1");
        when(projectLlmRouter.route(anyLong()))
                .thenReturn(new LlmEndpointConfig("test-provider", "nlstep-test", "k", "http://x", "config"));
    }

    private NlStepRequest request(long projectId) {
        return new NlStepRequest(projectId, "MOBILEWRIGHT", "打开首页并点击登录按钮，输入用户名 admin",
                List.of("launch", "tap", "input", "assertVisible"));
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "nlstep-test",
                500, 300, 900L),
                new LlmEndpointConfig("test-provider", "nlstep-test", "k", "http://x", "config"));
    }

    
    @Test
    void 关态裸打internal口_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.appauto.nl-step")).thenReturn("0");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 缺项目号_参数人话拒() {
        switchOn();
        NlStepRequest noProject = new NlStepRequest(null, "MOBILEWRIGHT", "点击登录按钮",
                List.of("tap"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(noProject));
        assertTrue(ex.getMessage().contains("项目号"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 空描述_参数人话拒() {
        switchOn();
        NlStepRequest blank = new NlStepRequest(PROJECT_BASE + 2, "MOBILEWRIGHT", "   ",
                List.of("tap"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(blank));
        assertTrue(ex.getMessage().contains("描述"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 空动词清单_参数人话拒() {
        switchOn();
        NlStepRequest noVerbs = new NlStepRequest(PROJECT_BASE + 3, "MOBILEWRIGHT", "点击登录", List.of());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(noVerbs));
        assertTrue(ex.getMessage().contains("动词"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 正常链_解析回传且留痕一行() {
        switchOn();
        long projectId = PROJECT_BASE + 4;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        {"steps":[
                          {"action":"app.launch","target":"登录页"},
                          {"action":"app.tap","target":{"text":"登录"},"retry":3,"waitAfterMs":500},
                          {"action":"app.input","value":"admin"}
                        ]}"""));

        Map<String, Object> draft = nlStepService.generate(request(projectId));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(3, steps.size());
        assertEquals("app.launch", steps.get(0).get("action"));
        assertEquals(3, ((Number) steps.get(1).get("retry")).intValue());
        assertEquals(500, ((Number) steps.get(1).get("waitAfterMs")).intValue());
        assertTrue(steps.get(1).get("target") instanceof Map<?, ?>);

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '自然语言步骤生成'",
                Integer.class, projectId);
        assertEquals(1, logged);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        List<ChatMessage> messages = captor.getValue().messages();
        String userText = messages.get(messages.size() - 1).textContent();
        assertTrue(userText.contains("打开首页并点击登录按钮"), userText);
        assertTrue(userText.contains("MOBILEWRIGHT"), userText);
        for (String verb : List.of("launch", "tap", "input", "assertVisible")) {
            assertTrue(userText.contains(verb), "prompt 缺下传动词 " + verb);
        }
        assertEquals("nlstep-test", captor.getValue().model());
    }

    
    @Test
    void 围栏包裹_剥壳解析回传() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("```json\n{\"steps\":[{\"action\":\"app.tap\",\"target\":\"确定\"}]}\n```"));

        Map<String, Object> draft = nlStepService.generate(request(PROJECT_BASE + 5));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(1, steps.size());
        assertEquals("app.tap", steps.get(0).get("action"));
    }

    
    @Test
    void 应答不可解析_格式错误拒() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉，我无法把这句话转成步骤"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    
    @Test
    void steps非数组_格式错误拒() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"steps\":\"点击登录按钮\"}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request(PROJECT_BASE + 7)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    
    @Test
    void 越界动词不在本段拦截_留给core复校() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"steps\":[{\"action\":\"app.swipe\"}]}\n"));

        Map<String, Object> draft = nlStepService.generate(request(PROJECT_BASE + 8));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals("app.swipe", steps.get(0).get("action"));
    }
}
