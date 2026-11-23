package com.sixbean.testpike.aiservice.upgradeadvisor.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.upgradeadvisor.api.dto.UpgradeAdvisorProfile;
import com.sixbean.testpike.aiservice.upgradeadvisor.api.dto.UpgradeAdvisorRequest;
import com.sixbean.testpike.aiservice.upgradeadvisor.domain.service.UpgradeAdvisorService;
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
class UpgradeAdvisorServiceImplTest {

    
    private static final long PROJECT_BASE = 989_500_000L;

    @Autowired
    private UpgradeAdvisorService upgradeAdvisorService;

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
        when(coreGateway.getSysConfig("ai.appauto.upgrade-advisor")).thenReturn("1");
        when(projectLlmRouter.route(anyLong()))
                .thenReturn(new LlmEndpointConfig("test-provider", "advisortest", "k", "http://x", "config"));
    }

    private UpgradeAdvisorRequest request(long projectId) {
        return new UpgradeAdvisorRequest(projectId, "2.0.0",
                List.of("app.tap 定位参数由 xpath 改为 selector 必填", "移除 app.swipe_legacy 动词"),
                new UpgradeAdvisorProfile(42,
                        Map.of("app.tap", 128, "app.input", 37, "app.swipe_legacy", 9),
                        Map.of("MOBILEWRIGHT", 61, "APPIUM", 12), 7, 5));
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "test-provider", "advisortest",
                1500, 600, 2100L),
                new LlmEndpointConfig("test-provider", "advisortest", "k", "http://x", "config"));
    }

    
    @Test
    void 关态裸打internal口_人话拒且零模型调用() {
        when(coreGateway.getSysConfig("ai.appauto.upgrade-advisor")).thenReturn("0");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> upgradeAdvisorService.advise(request(PROJECT_BASE + 1)));
        assertTrue(ex.getMessage().contains("未开启"), ex.getMessage());
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode().intValue());
        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 参数人话闸_零外呼() {
        switchOn();
        UpgradeAdvisorRequest noProject = new UpgradeAdvisorRequest(null, "2.0.0",
                List.of("x"), new UpgradeAdvisorProfile(1, Map.of(), Map.of(), 0, 0));
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> upgradeAdvisorService.advise(noProject));
        assertTrue(ex1.getMessage().contains("项目号"), ex1.getMessage());

        UpgradeAdvisorRequest noVersion = new UpgradeAdvisorRequest(PROJECT_BASE + 2, "  ",
                List.of("x"), new UpgradeAdvisorProfile(1, Map.of(), Map.of(), 0, 0));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> upgradeAdvisorService.advise(noVersion));
        assertTrue(ex2.getMessage().contains("版本"), ex2.getMessage());

        UpgradeAdvisorRequest noProfile = new UpgradeAdvisorRequest(PROJECT_BASE + 3, "2.0.0",
                List.of("x"), null);
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> upgradeAdvisorService.advise(noProfile));
        assertTrue(ex3.getMessage().contains("画像"), ex3.getMessage());

        verifyNoInteractions(llmFallbackChain);
    }

    
    @Test
    void 正常链_解析回传且留痕一行() {
        switchOn();
        long projectId = PROJECT_BASE + 4;
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        {"risks":["清单移除了 app.swipe_legacy，你项目里有 9 步在用它，升级后会直接报错",
                        "app.tap 定位参数变更影响 128 个 tap 步，建议升级前全量回归"],
                        "summary":"两个破坏性变更都命中你的存量用例，建议先在预发环境验证再升级"}"""));

        Map<String, Object> advice = upgradeAdvisorService.advise(request(projectId));

        @SuppressWarnings("unchecked")
        List<Object> risks = (List<Object>) advice.get("risks");
        assertEquals(2, risks.size());
        assertTrue(String.valueOf(risks.get(0)).contains("app.swipe_legacy"));
        assertEquals("两个破坏性变更都命中你的存量用例，建议先在预发环境验证再升级",
                advice.get("summary"));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain).chatHandle(captor.capture(), anyLong(), any());
        String promptText = captor.getValue().messages().toString();
        assertTrue(promptText.contains("移除 app.swipe_legacy 动词"), "prompt 应含破坏性变更原文");
        assertTrue(promptText.contains("42"), "prompt 应含画像实算的用例基数");
        assertTrue(promptText.contains("app.tap"), "prompt 应含动词使用计数");
        assertTrue(promptText.contains("MOBILEWRIGHT"), "prompt 应含引擎使用计数");
        assertTrue(promptText.contains("2.0.0"), "prompt 应含目标版本号语境");

        Integer logged = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_call_log WHERE project_id = ? AND function = '升级顾问风险清单'",
                Integer.class, projectId);
        assertEquals(1, logged);
    }

    
    @Test
    void 围栏包裹_容错剥壳解析() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("""
                        ```json
                        {"risks":["无命中风险，可直接升级"],"summary":"画像与清单无交集"}
                        ```"""));

        Map<String, Object> advice = upgradeAdvisorService.advise(request(PROJECT_BASE + 5));
        @SuppressWarnings("unchecked")
        List<Object> risks = (List<Object>) advice.get("risks");
        assertEquals(1, risks.size());
        assertEquals("无命中风险，可直接升级", risks.get(0));
    }

    
    @Test
    void 结构违例_格式错误人话抛() {
        switchOn();
        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("抱歉我做不到"));
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> upgradeAdvisorService.advise(request(PROJECT_BASE + 6)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex1.getCode().intValue());

        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"summary\":\"只有摘要没有风险清单\"}"));
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> upgradeAdvisorService.advise(request(PROJECT_BASE + 7)));
        assertTrue(ex2.getMessage().contains("risks"), ex2.getMessage());

        when(llmFallbackChain.chatHandle(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(handle("{\"risks\":[{\"text\":\"不是人话字符串\"}],\"summary\":\"x\"}"));
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> upgradeAdvisorService.advise(request(PROJECT_BASE + 8)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, ex3.getCode().intValue());
    }
}
