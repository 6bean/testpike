package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.WebElementLedgerSummaryBridge;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * prompt, the artefact that lands in the script store must keep the
 * reference form for ledger-backed locators. The string placeholder
 * {@code {{ELEM:name}}} is the addressable form the dispatch snapshot later
 * resolves (normalisation single source: WebElementDispatchSnapshot, pinned
 * in its own S1 suite) — so the generation side is pinned here on the byte
 * shape that actually reaches saveScript, not on a re-implemented resolver.
 * <p>Ratio posture: denominator = locator-bearing steps (placeholder targets
 * or inline locator objects with a non-empty chain); assertion-only targets
 * ({@code expect{text}}) and setup goto urls stay out of the measure. A full
 * quote run must land at 1.0; a mixed invention run must report its true
 * share — never silently inflate it.
 * <p>L2 posture: mocked CoreGateway/LlmFallbackChain isolate infrastructure
 * only; parser, validators and the summary bridge are the real components
 * (same seam discipline as WebElementLedgerSummaryWiringTest).
 */
class WebElementGenerationReferenceRatioTest {

    private static final long TASK_ID = 99L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 12L;
    private static final long CASE_ID = 991L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final WebElementLedgerSummaryBridge bridge =
            new WebElementLedgerSummaryBridge(coreGateway, new ObjectMapper());

    private final AiGenServiceImpl service = GenWiringHarness.assemble(
            coreGateway, llmChain, llmRouter, null, null, null, null, bridge);
    private final ObjectMapper mapper = new ObjectMapper();

    /** Ledger rows the prompt-summary door would return for this project. */
    private static List<Map<String, Object>> confirmedRows() {
        return List.of(
                Map.of("name", "登录按钮", "pageKey", "login",
                        "anchors", List.of(Map.of("type", "css", "value", "#login-btn"))),
                Map.of("name", "用户名输入框", "pageKey", "login",
                        "anchors", List.of(Map.of("type", "testid", "value", "username"))));
    }

    /** Answers every stage-2 pass with the given script JSON, then captures the save. */
    private String runAndCaptureSavedScript(String stage2Json) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 0, null, null, null));
        when(coreGateway.getCase(CASE_ID)).thenReturn(Map.of("title", "登录用例"));
        when(coreGateway.getLedgerPromptSummary(eq(PROJECT_ID), isNull(), anyInt()))
                .thenReturn(confirmedRows());
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String content = r.messages().get(0).content().contains("STAGE1_CASE_JSON")
                            ? "{}" : stage2Json;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L),
                            GenWiringHarness.ENDPOINT);
                });

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 2,
                "重生成登录脚本", null, CASE_ID));

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), saved.capture(),
                eq(1), eq(OPERATOR_ID));
        return saved.getValue();
    }

    /** Mechanical split of the saved artefact: [reference, inline-locator] step counts. */
    private int[] countLocatorForms(String scriptJson) throws Exception {
        JsonNode steps = mapper.readTree(scriptJson).path("steps");
        int reference = 0;
        int inline = 0;
        for (JsonNode step : steps) {
            JsonNode target = step.path("target");
            if (target.isTextual() && target.asText().startsWith("{{ELEM:")) {
                reference++;
            } else if (target.isObject()
                    && (target.path("by").asText("").equals("element")
                        || target.path("chain").isArray() && !target.path("chain").isEmpty())) {
                inline++;
            }
        }
        return new int[]{reference, inline};
    }

    @Test
    void fullyQuotedArtifactLandsAtFullReferenceShare() throws Exception {
        String saved = runAndCaptureSavedScript("""
                {"version":"v2","caseId":"TC-REG-991","endType":"web",
                 "setup":[{"action":"goto","target":{"url":"{{ENV:URL}}/login"}}],
                 "steps":[
                   {"action":"input","target":"{{ELEM:用户名输入框}}","value":"u"},
                   {"action":"click","target":"{{ELEM:登录按钮}}"},
                   {"action":"expect","target":{"text":"首页"}}
                 ],
                 "env":{"ENV":"dev","PACK":"generic","DATA":"default"}}""");

        int[] forms = countLocatorForms(saved);
        assertEquals(2, forms[0], "两个建档元素必须都以引用态落库: " + saved);
        assertEquals(0, forms[1], "全引用产物不得混入内联定位");
        assertEquals(1.0d, forms[0] / (double) (forms[0] + forms[1]),
                "已建档元素产物引用态占比必须为 1");
        assertTrue(saved.contains("{{ELEM:用户名输入框}}") && saved.contains("{{ELEM:登录按钮}}"),
                "名称寻址字节不得被生成链改写（派发侧按名解析单源）");
        assertTrue(saved.contains("\"text\":\"首页\""), "expect 断言位不参与占比、原样保留");
    }

    @Test
    void mixedInventionReportsTrueReferenceShare() throws Exception {
        String saved = runAndCaptureSavedScript("""
                {"version":"v2","caseId":"TC-REG-991","endType":"web",
                 "setup":[{"action":"goto","target":{"url":"{{ENV:URL}}/login"}}],
                 "steps":[
                   {"action":"click","target":"{{ELEM:登录按钮}}"},
                   {"action":"click","target":{"css":"#ad-close","chain":["css"]}}
                 ],
                 "env":{"ENV":"dev","PACK":"generic","DATA":"default"}}""");

        int[] forms = countLocatorForms(saved);
        assertEquals(1, forms[0], "引用位照单源占位形态落库");
        assertEquals(1, forms[1], "失配自造锚以内联形态落库（候选入册归 core 保存链谱）");
        assertEquals(0.5d, forms[0] / (double) (forms[0] + forms[1]),
                "混合产物必须报真实占比，不得虚报");
    }
}
