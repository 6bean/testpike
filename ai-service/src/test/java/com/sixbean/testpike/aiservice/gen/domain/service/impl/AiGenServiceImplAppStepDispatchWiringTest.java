package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.AppStepGenBridge;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class AiGenServiceImplAppStepDispatchWiringTest {

    private static final long TASK_ID = 105L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long CASE_ID = 5500L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);

    private final List<String> stage2Prompts = new ArrayList<>();

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private AiGenServiceImpl service() {
        return new AiGenServiceImpl(llmChain, llmRouter, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway),
                null, null, null, null, null, null, null, null, null,
                new AppStepGenBridge(coreGateway));
    }

    
    private void stubCase(String automationTarget, String engineCode) {
        when(coreGateway.getCase(CASE_ID)).thenReturn(Map.of(
                "id", CASE_ID, "title", "登录流程",
                "automationTarget", automationTarget, "appautoEngine", engineCode));
    }

    private void stubEngines(List<Map<String, Object>> engines) {
        when(coreGateway.listAppautoEngines()).thenReturn(engines);
    }

    private static Map<String, Object> engine(String code, Map<String, Boolean> caps) {
        return Map.of("code", code, "name", code + " 引擎", "capabilities", caps, "status", 1);
    }

    
    private void stubLlm(String stage2Output) {
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any()))
                .thenAnswer(inv -> new LlmChatHandle(
                        new LlmChatResult(stage2Output, "openai", "m", 100, 50, 20L), endpoint()));
    }

    private static final String APP_SCRIPT = """
            {"version":"v2","caseId":"TC-A5-1","endType":"mobile",
             "env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"app.launch","description":"启动应用","target":{"appPackage":"com.demo","chain":["appPackage"]}}],
             "steps":[
               {"action":"app.tap","description":"点击登录按钮","target":{"resourceId":"com.demo:id/login","chain":["resourceId"]}},
               {"action":"app.assertText","description":"断言欢迎文案","target":{"text":"欢迎"},"value":"欢迎"}
             ]}""";

    private AiGenMessage scriptTask() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 2, "", null, CASE_ID);
    }

    private static final Map<String, Boolean> FULL_CAPS = Map.of(
            "tap", true, "input", true, "assertText", true, "launch", true,
            "extractText", false, "swipe", false,
            "flutter", true, "record-native", false);

    private void runStage2() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        service().generate(scriptTask());
    }

    private String stage2Prompt() {
        var captor = org.mockito.ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(llmChain).chatHandle(captor.capture(), anyLong(), any());
        return captor.getValue().messages().get(1).content();
    }

    @Test
    void switchOffKeepsWebVocabularyEvenForAppTarget() {
        when(coreGateway.getSysConfig(any())).thenReturn(null);
        stubCase("app", "MOBILEWRIGHT");
        stubEngines(List.of(engine("MOBILEWRIGHT", FULL_CAPS)));
        stubLlm(APP_SCRIPT);
        runStage2();
        String prompt = stage2Prompt();
        assertFalse(prompt.contains("目标引擎"), "关态仍渲染分流段=存量零变化红线失守");
        assertFalse(prompt.contains("resource-id 命名规约"), "关态不得注入 app 定位规约");
    }

    @Test
    void switchOnAppTargetPrunesVerbsByEngineBitmap() {
        when(coreGateway.getSysConfig("ai.appauto.gen-steps")).thenReturn("1");
        stubCase("app", "mobilewright");
        stubEngines(List.of(engine("MOBILEWRIGHT", FULL_CAPS),
                engine("MAESTRO", Map.of("tap", true))));
        stubLlm(APP_SCRIPT);
        runStage2();
        String prompt = stage2Prompt();
        assertTrue(prompt.contains("app.tap") && prompt.contains("app.assertText"),
                "位图内动词必须进裁剪清单: " + prompt);
        assertFalse(prompt.contains("app.extractText"), "位图外动词混入=裁剪失守");
        assertFalse(prompt.contains("app.swipe"), "位图外动词混入=裁剪失守");
        assertTrue(prompt.contains("resource-id"), "resource-id 命名规约段必须入提示词");
        assertTrue(prompt.contains("flutter"), "特性旗可用面必须渲染（能力位数据驱动）");
        assertFalse(prompt.contains("record-native"), "位图 false 的特性旗不得标可用");
    }

    @Test
    void switchOnMpTargetAlsoDispatches() {
        when(coreGateway.getSysConfig("ai.appauto.gen-steps")).thenReturn("1");
        stubCase("mp", "MINIUM");
        stubEngines(List.of(engine("MINIUM", FULL_CAPS)));
        stubLlm(APP_SCRIPT);
        runStage2();
        assertTrue(stage2Prompt().contains("app.tap"), "小程序目标同属分流面");
    }

    @Test
    void harmonyTargetAlsoDispatches() {
        when(coreGateway.getSysConfig("ai.appauto.gen-steps")).thenReturn("1");
        stubCase("harmony", "HARMONY_NEXT");
        stubEngines(List.of(engine("HARMONY_NEXT", FULL_CAPS)));
        stubLlm(APP_SCRIPT);
        runStage2();
        String prompt = stage2Prompt();
        assertTrue(prompt.contains("app.tap"), "鸿蒙目标同属分流面（T342 扩值域后不得静默走 Web 语义）");
        assertTrue(prompt.contains("自动化目标=harmony"), "分流段须如实渲染鸿蒙目标");
    }

    @Test
    void webTargetNeverDispatchesEvenWhenSwitchOn() {
        when(coreGateway.getSysConfig("ai.appauto.gen-steps")).thenReturn("1");
        stubCase("web", "MOBILEWRIGHT");
        stubEngines(List.of(engine("MOBILEWRIGHT", FULL_CAPS)));
        stubLlm(APP_SCRIPT);
        runStage2();
        assertFalse(stage2Prompt().contains("目标引擎"), "web 目标零分流");
    }

    @Test
    void bitmapMissFailSoftKeepsGenerationAlive() {
        when(coreGateway.getSysConfig("ai.appauto.gen-steps")).thenReturn("1");
        stubCase("app", "GHOST");
        stubEngines(List.of(engine("MOBILEWRIGHT", FULL_CAPS)));
        stubLlm(APP_SCRIPT);
        runStage2();
        assertFalse(stage2Prompt().contains("目标引擎"), "查无依据不发第二套裁剪");
    }

    @Test
    void defaultEngineFillsWhenCaseHasNoEngineCode() {
        when(coreGateway.getSysConfig("ai.appauto.gen-steps")).thenReturn("1");
        stubCase("app", "");
        stubEngines(List.of(engine("MAESTRO", Map.of("tap", true, "assertVisible", true))));
        stubLlm(APP_SCRIPT);
        runStage2();
        String prompt = stage2Prompt();
        assertTrue(prompt.contains("app.tap") && prompt.contains("app.assertVisible"),
                "用例未选引擎=目录首位（系统默认）裁剪");
        assertFalse(prompt.contains("app.input"), "默认引擎位图无 input 不得混入");
    }
}
