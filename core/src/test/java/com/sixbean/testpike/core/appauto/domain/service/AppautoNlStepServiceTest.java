package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoNlStepView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceNlStepClient;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoNlStepServiceTest {

    @Autowired
    private AppautoNlStepService nlStepService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @MockitoBean
    private AiServiceNlStepClient aiServiceNlStepClient;

    @BeforeEach
    void switchOn() {
        configCacheHolder.put(AppautoConfigReader.KEY_NL_STEP, "1");
    }

    @AfterEach
    void resetSwitch() {
        configCacheHolder.remove(AppautoConfigReader.KEY_NL_STEP);
    }

    private AppautoNlStepRequest request(String engineCode, String description) {
        return new AppautoNlStepRequest(7L, engineCode, description);
    }

    private AppautoNlStepView viewWith(String action) {
        return new AppautoNlStepView(List.of(
                Map.of("action", action, "target", Map.of("text", "登录"))));
    }

    @Test
    void switchOffRejectsHumanWordsAndNeverCallsModel() {
        configCacheHolder.put(AppautoConfigReader.KEY_NL_STEP, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("MOBILEWRIGHT", "点击登录按钮")));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("未开启：请在「系统管理 → 系统配置」搜索") && ex.getMessage().contains("ai.appauto.nl-step"), "关态文案须人话说明开关未开：" + ex.getMessage());
        verifyNoInteractions(aiServiceNlStepClient);
    }

    @Test
    void blankDescriptionRejectedBeforeAnyLookup() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("MOBILEWRIGHT", "   ")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        verifyNoInteractions(aiServiceNlStepClient);
    }

    @Test
    void blankEngineRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request(null, "点击登录按钮")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        verifyNoInteractions(aiServiceNlStepClient);
    }

    @Test
    void unknownEngineRejectsHuman404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("NO_SUCH_ENGINE", "点击登录按钮")));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        verifyNoInteractions(aiServiceNlStepClient);
    }

    @Test
    void disabledEngineRejectedWithHumanWords() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("HYPIUM", "点击登录按钮")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("下架"), "须点名引擎已下架：" + ex.getMessage());
        verifyNoInteractions(aiServiceNlStepClient);
    }

    @Test
    void allowedVerbsAreIrIntersectEngineCapabilities() {
        when(aiServiceNlStepClient.generate(any())).thenReturn(viewWith("app.tap"));
        nlStepService.generate(request("mobilewright", "点击登录按钮"));
        ArgumentCaptor<AppautoNlStepLlmRequest> captor =
                ArgumentCaptor.forClass(AppautoNlStepLlmRequest.class);
        verify(aiServiceNlStepClient).generate(captor.capture());
        AppautoNlStepLlmRequest sent = captor.getValue();
        assertEquals(List.of(
                "launch", "close", "tap", "longPress", "doubleTap", "input", "clearInput",
                "swipe", "scroll", "back", "screenshot", "assertVisible", "assertNotVisible",
                "assertText", "waitVisible", "extractText",
                "deepLink", "openUrl", "rotation", "home", "clearData", "grantPermission",
                "wakeUnlock", "networkProfile", "install", "uninstall", "verifyVersion"),
                sent.allowedVerbs(),
                "下传动词集必须精确等于 IR_VERBS ∩ capabilities，禁手抄禁模型自律");
        assertEquals("MOBILEWRIGHT", sent.engineCode(), "引擎 code 归一大写后下传");
        assertEquals(7L, sent.projectId());
    }

    @Test
    void outOfBaselineVerbFailClosedRejects() {
        when(aiServiceNlStepClient.generate(any())).thenReturn(viewWith("app.drag"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("MOBILEWRIGHT", "拖动物品到背包")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("app.drag"), "点名越界动词：" + ex.getMessage());
    }

    @Test
    void nonAppVerbFailClosedRejects() {
        when(aiServiceNlStepClient.generate(any())).thenReturn(viewWith("click"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("MOBILEWRIGHT", "点击登录按钮")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("click"), "点名非法动词：" + ex.getMessage());
    }

    @Test
    void stepWithoutActionFailClosedRejects() {
        when(aiServiceNlStepClient.generate(any()))
                .thenReturn(new AppautoNlStepView(List.of(Map.of("target", Map.of("text", "登录")))));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("MOBILEWRIGHT", "点击登录按钮")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void emptyModelAnswerRejectedHumanWords() {
        when(aiServiceNlStepClient.generate(any())).thenReturn(new AppautoNlStepView(List.of()));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> nlStepService.generate(request("MOBILEWRIGHT", "点击登录按钮")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void legalAnswerReturnedAsDraftVerbatim() {
        AppautoNlStepView draft = new AppautoNlStepView(List.of(
                Map.of("action", "app.tap", "target", Map.of("text", "登录")),
                Map.of("action", "app.input", "target", Map.of("testTag", "user"),
                        "value", "admin")));
        when(aiServiceNlStepClient.generate(any())).thenReturn(draft);
        AppautoNlStepView result = nlStepService.generate(
                request("MOBILEWRIGHT", "点击登录按钮，在用户名输入框输入 admin"));
        assertEquals(draft.steps(), result.steps(), "合法应答原样回传草稿，core 禁二次翻译");
    }
}
