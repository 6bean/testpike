package com.sixbean.testpike.aiservice.popuplearn.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.popuplearn.api.dto.PopupLearnRequest;
import com.sixbean.testpike.aiservice.popuplearn.domain.service.PopupLearnService;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest
class PopupLearnServiceImplTest {

    
    private static final long PROJECT_BASE = 989_300_000L;

    @Autowired
    private PopupLearnService popupLearnService;

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
        when(coreGateway.getSysConfig("ai.appauto.popup-rule-learn")).thenReturn("1");
    }

    private PopupLearnRequest request(long projectId) {
        return new PopupLearnRequest(903L, projectId, List.of("aGk=", "aGkz"));
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "vision-test",
                200, 60, 900L),
                new LlmEndpointConfig("test-provider", "vision-test", "k", "http://x", "config"));
    }

    
    @Test
    void 总闸关_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.vision.enabled")).thenReturn("0");
        when(coreGateway.getSysConfig("ai.appauto.popup-rule-learn")).thenReturn("1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupLearnService.learn(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 分开关关_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.vision.enabled")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.appauto.popup-rule-learn")).thenReturn("0");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupLearnService.learn(request(PROJECT_BASE + 2)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 模型键空_人话拒零外呼() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupLearnService.learn(request(PROJECT_BASE + 3)));
        assertTrue(ex.getMessage().contains("视觉模型未配置"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 帧清单空_参数人话拒() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");

        PopupLearnRequest noFrames = new PopupLearnRequest(903L, PROJECT_BASE + 4, List.of());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupLearnService.learn(noFrames));
        assertTrue(ex.getMessage().contains("帧"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 项目号缺失_参数人话拒() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");

        PopupLearnRequest noProject = new PopupLearnRequest(903L, null, List.of("aGk="));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupLearnService.learn(noProject));
        assertTrue(ex.getMessage().contains("项目"), ex.getMessage());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 正常链_原样回传且留痕一行() {
        switchesOn();
        long projectId = PROJECT_BASE + 5;
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        ```json
                        {"rule":{"detect":{"textContains":"用户隐私政策"},\
                        "dismiss":{"action":"app.tap","target":{"text":"同意"}}},\
                        "title":"隐私弹窗-同意按钮"}
                        """));

        Map<String, Object> got = popupLearnService.learn(request(projectId));

        Object rule = got.get("rule");
        assertTrue(rule instanceof Map<?, ?>, "rule 段原样透传（结构闸单点在 core，本段禁二套）");
        assertEquals("隐私弹窗-同意按钮", got.get("title"));

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '弹窗规则学习'",
                Integer.class, projectId);
        assertEquals(1, logged);

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        List<ChatMessage> messages = captor.getValue().messages();
        List<com.sixbean.testpike.aiservice.llm.api.dto.ContentPart> parts =
                messages.get(messages.size() - 1).parts();
        long imageParts = parts.stream()
                .filter(p -> "image_url".equals(p.type()))
                .peek(p -> assertTrue(p.imageUrl().url().startsWith("data:image/png;base64,"),
                        "帧 base64 直拼 data URL（候选 A 同律：零新存储件）"))
                .count();
        assertEquals(2, imageParts, "监看帧全部逐帧进 vision 段（剔除敏感帧在 core 侧已完成）");
        assertNotNull(captor.getValue().model());
    }

    
    @Test
    void 模型输出不可解析_格式错误拒() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("这几张图里我没看到弹窗"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupLearnService.learn(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }

    
    @Test
    void 输出非对象_格式错误拒() {
        switchesOn();
        when(coreGateway.getSysConfig("ai.vision.model")).thenReturn("vision-test");
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("[1,2,3]"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> popupLearnService.learn(request(PROJECT_BASE + 7)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex.getCode().intValue());
    }
}
