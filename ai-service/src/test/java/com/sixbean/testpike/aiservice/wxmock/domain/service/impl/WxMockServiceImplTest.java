package com.sixbean.testpike.aiservice.wxmock.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.wxmock.api.dto.WxMockRequest;
import com.sixbean.testpike.aiservice.wxmock.domain.service.WxMockService;
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
class WxMockServiceImplTest {

    
    private static final long PROJECT_BASE = 989_400_000L;

    @Autowired
    private WxMockService wxMockService;

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
        when(coreGateway.getSysConfig("ai.appauto.wx-mock")).thenReturn("1");
        when(projectLlmRouter.route(anyLong()))
                .thenReturn(new LlmEndpointConfig("test-provider", "wxmock-test", "k", "http://x", "config"));
    }

    private WxMockRequest request(long projectId) {
        return new WxMockRequest(projectId, "/api/order/list", "POST",
                Map.of("request", Map.of("userId", "string", "page", "number"),
                        "response", Map.of("orderId", "string", "buyerIdCard", "string",
                                "amount", "number", "count", "number")),
                List.of("idCard", "bankCard", "phone", "chineseName"));
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "wxmock-test",
                900, 500, 1300L),
                new LlmEndpointConfig("test-provider", "wxmock-test", "k", "http://x", "config"));
    }

    
    @Test
    void 关态裸打internal口_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.appauto.wx-mock")).thenReturn("0");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> wxMockService.generate(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 参数人话闸_零外呼() {
        switchOn();
        WxMockRequest noProject = new WxMockRequest(null, "/api/x", "GET",
                Map.of("response", Map.of("a", "string")), List.of("idCard"));
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> wxMockService.generate(noProject));
        assertTrue(ex1.getMessage().contains("项目号"), ex1.getMessage());

        WxMockRequest noContract = new WxMockRequest(PROJECT_BASE + 2, "/api/x", "GET",
                Map.of(), List.of("idCard"));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> wxMockService.generate(noContract));
        assertTrue(ex2.getMessage().contains("契约"), ex2.getMessage());

        WxMockRequest noFunctions = new WxMockRequest(PROJECT_BASE + 3, "/api/x", "GET",
                Map.of("response", Map.of("a", "string")), List.of());
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> wxMockService.generate(noFunctions));
        assertTrue(ex3.getMessage().contains("函数清单"), ex3.getMessage());

        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 正常链_解析回传且留痕一行() {
        switchOn();
        long projectId = PROJECT_BASE + 4;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        {"mocks":[
                          {"scenario":"normal","response":{"orderId":"W20261004001",
                            "buyerIdCard":"${gen.idCard(seed=7)}","amount":99.5,"count":2}},
                          {"scenario":"boundary","response":{"orderId":"","buyerIdCard":"",
                            "amount":0,"count":0}}]}"""));

        Map<String, Object> draft = wxMockService.generate(request(projectId));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mocks = (List<Map<String, Object>>) draft.get("mocks");
        assertEquals(2, mocks.size());
        assertEquals("normal", mocks.get(0).get("scenario"));
        assertEquals(99.5, ((Number) ((Map<?, ?>) mocks.get(0).get("response")).get("amount")).doubleValue(), 0.0001);
        assertEquals(2, ((Number) ((Map<?, ?>) mocks.get(0).get("response")).get("count")).intValue());

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        String promptText = captor.getValue().messages().toString();
        assertTrue(promptText.contains("buyerIdCard"), "prompt 应含接口契约样例字段");
        assertTrue(promptText.contains("bankCard"), "prompt 应含 core 下传的 genkit 函数清单");
        assertTrue(promptText.contains("/api/order/list"), "prompt 应含接口路径语境");

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '小程序mock数据集生成'",
                Integer.class, projectId);
        assertEquals(1, logged);
    }

    
    @Test
    void 围栏包裹_容错剥壳解析() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        ```json
                        {"mocks":[{"scenario":"empty","response":{}}]}
                        ```"""));

        Map<String, Object> draft = wxMockService.generate(request(PROJECT_BASE + 5));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mocks = (List<Map<String, Object>>) draft.get("mocks");
        assertEquals(1, mocks.size());
        assertEquals("empty", mocks.get(0).get("scenario"));
    }

    
    @Test
    void 结构违例_格式错误人话拒() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉我做不到"));
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> wxMockService.generate(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex1.getCode().intValue());

        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"mocks\":{\"scenario\":\"normal\"}}"));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> wxMockService.generate(request(PROJECT_BASE + 7)));
        assertTrue(ex2.getMessage().contains("mocks"), ex2.getMessage());
    }

 
    @Test
    void 假号应答_原样回传不做第二套判定() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        {"mocks":[{"scenario":"normal",
                          "response":{"buyerIdCard":"110101199003071234"}}]}"""));

        Map<String, Object> draft = wxMockService.generate(request(PROJECT_BASE + 8));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mocks = (List<Map<String, Object>>) draft.get("mocks");
        assertEquals(1, mocks.size());
        assertEquals("110101199003071234",
                ((Map<?, ?>) mocks.get(0).get("response")).get("buyerIdCard"));
    }
}
