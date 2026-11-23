package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.testsupport.LogbackSink;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Structured-decode enforcement notice suite.
 * <p>Live probes proved some gateway endpoints accept the response_format
 * block on the wire yet never enforce the schema: the first draft still
 * breaks the shape gate (steps as string arrays, key renames, missing
 * required fields). The refusal WARN only covers hard 400 refusals, so
 * without a second anchor the accept-but-not-enforce class reads as full
 * support. The ring must therefore emit exactly one WARN when the block was
 * mounted and the first verifier pass still carries format-class disputes,
 * giving the L3 window a greppable truth anchor.
 * <p>L2 stance: mock CoreGateway/LlmFallbackChain (infrastructure only);
 * parser, self-check, ring and GenStructuredOutput are the real components.
 */
class AiGenServiceImplStructuredDecodeEnforcementNoticeTest {

    private static final long TASK_ID = 91L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 911L;

    /** Anchor marker of the non-enforcement WARN (shared with the L3 script). */
    private static final String NOTICE_MARK = "端点未强制";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);

    private final LlmEndpointConfig endpoint = new LlmEndpointConfig(
            "openai", "m", "k", "http://localhost:4000/v1",
            LlmEndpointConfig.SOURCE_CONFIG);

    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway));

    private final List<Boolean> stage1Mounted = new ArrayList<>();

    /** Shape-valid stage1 draft (mirrors the sibling wiring suites). */
    private static final String STAGE1_JSON = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Draft that violates the mounted schema: required title absent. */
    private static final String STAGE1_SHAPE_BREAKING = """
            {"precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页"}""";

    private static final String STAGE2_CLEAN = """
            {"version":"v2","caseId":"TC-notice-911","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[
               {"action":"wait","description":"等待登录表单出现","target":{"css":"#username","chain":["css"]},"state":"visible"},
               {"action":"expect","description":"断言跳转首页","target":{"text":"首页"},"value":"首页"}
             ]}""";

    private void stubRouting(String gateRaw) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "notice", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-notice-911"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                GenStructuredOutput.GATE_KEY.equals(inv.getArgument(0)) ? gateRaw : null);
        int[] stage2Idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    if (r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        stage1Mounted.add(r.responseFormat() != null);
                        return handle(lastStage1Draft());
                    }
                    stage2Idx[0]++;
                    return handle(STAGE2_CLEAN);
                });
    }

    /** The draft this suite feeds stage1 — flipped per test. */
    private String lastStage1Draft = STAGE1_JSON;

    private String lastStage1Draft() {
        return lastStage1Draft;
    }

    private LlmChatHandle handle(String content) {
        return new LlmChatHandle(new LlmChatResult(content, "openai", "m", 100, 50, 20L),
                endpoint);
    }

    private AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null);
    }

    @Test
    void emitsOneNoticeWhenBlockMountedButFirstDraftBreaksShape() {
        lastStage1Draft = STAGE1_SHAPE_BREAKING;
        try (LogbackSink sink = LogbackSink.attach(AiGenServiceImpl.class)) {
            stubRouting(null);
            assertThrows(ServiceException.class, () -> service.generate(message()));
            assertTrue(stage1Mounted.contains(Boolean.TRUE),
                    "gate on + capable row must mount the response_format block");
            assertEquals(1, sink.warnsContaining(NOTICE_MARK).size(),
                    "exactly one non-enforcement notice per task: " + sink.warns());
        }
    }

    @Test
    void staysSilentWhenMountedBlockIsActuallyEnforced() {
        lastStage1Draft = STAGE1_JSON;
        try (LogbackSink sink = LogbackSink.attach(AiGenServiceImpl.class)) {
            stubRouting(null);
            service.generate(message());
            assertTrue(stage1Mounted.contains(Boolean.TRUE),
                    "the success path must still ride the mounted block");
            assertEquals(0, sink.warnsContaining(NOTICE_MARK).size(),
                    "enforced endpoint must not be accused: " + sink.warns());
        }
    }

    @Test
    void staysSilentWhenGateOffSoNothingWasMounted() {
        lastStage1Draft = STAGE1_SHAPE_BREAKING;
        try (LogbackSink sink = LogbackSink.attach(AiGenServiceImpl.class)) {
            stubRouting("0");
            assertThrows(ServiceException.class, () -> service.generate(message()));
            assertEquals(List.of(), stage1Mounted.stream().filter(Boolean::booleanValue).toList(),
                    "gate off must leave the request byte-identical (no block)");
            assertEquals(0, sink.warnsContaining(NOTICE_MARK).size(),
                    "no mount, no notice: " + sink.warns());
        }
    }
}
