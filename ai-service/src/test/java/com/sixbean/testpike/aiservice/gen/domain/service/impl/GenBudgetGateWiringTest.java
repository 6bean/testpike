package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3 wiring proof: the generation plane's single model calls (stage1
 * direct pass + every stage2 ring pass) ride the SAME prompt-budget fitting
 * seam the dialog loops use — the gate component itself is untouched, only a
 * new consumer entry. Proved postures: the provider door receives the FITTED
 * packet with the project id handed to fit; a vendor context-overflow 400
 * gets exactly ONE healed retry through the shared send seam; the legacy
 * assembly (no fitting slot) keeps byte-identical "send as built" posts and
 * rethrows the same error instance — nothing is ever swallowed.
 * L2 posture: infrastructure mocks via {@link GenWiringHarness} single
 * source; every judgement component stays the real one.
 */
class GenBudgetGateWiringTest {

    private static final long TASK_ID = 173L;
    private static final long PROJECT_ID = 4L;
    private static final long OPERATOR_ID = 12L;
    private static final long CASE_ID = 5701L;
    private static final long NEW_CASE_ID = 5702L;
    private static final String REQUIREMENT_TEXT = "用户输入正确账号密码登录成功";

    /** Marker appended by the stub fitting — proves the send rode the seam. */
    private static final String FIT_MARKER = "\n〔预算闸桩：已压缩〕";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();

    private final List<ChatCompletionRequest> sentRequests = new ArrayList<>();

    /** Minimal stored-case row for the regenerate (stage2-only) flow. */
    private static Map<String, Object> caseRow() {
        return Map.of(
                "id", CASE_ID,
                "title", "下单流程",
                "precondition", "用户已登录",
                "expectedResult", "订单创建成功",
                "priority", 1,
                "confidence", 2,
                "endType", "web",
                "steps", List.of(Map.of("stepNo", 1, "description", "点击立即购买按钮", "isAssert", 0)));
    }

    @BeforeEach
    void wireInfrastructure() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getSysConfig(any())).thenReturn(null);
        when(coreGateway.getSysConfig(GenStructuredOutput.GATE_KEY)).thenReturn("0");
        when(coreGateway.getCase(CASE_ID)).thenReturn(caseRow());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "budget-gate", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-budget-5702"));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    sentRequests.add(inv.getArgument(0));
                    return GenWiringHarness.handle(replyFor(inv.getArgument(0)));
                });
    }

    /** Stage1 passes carry the case-JSON marker in the system row; stage2 is
     *  answered with the shared clean action JSON. */
    private static String replyFor(ChatCompletionRequest request) {
        return request.messages().get(0).content().contains("STAGE1_CASE_JSON")
                ? """
                    {"title":"登录成功","precondition":"登录页可访问",
                     "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                              {"stepNo":2,"description":"断言首页","isAssert":1}],
                     "expectedResult":"登录成功","priority":0,"confidence":1,"endType":"web"}"""
                : GenWiringHarness.STAGE2_CLEAN;
    }

    /** Fitting stub: fit marks the packet, heal folds it once. */
    private static LoopContextFitting markingFitting(int[] fits, int[] heals) {
        return new LoopContextFitting() {
            @Override
            public ChatCompletionRequest fit(ChatCompletionRequest request, long projectId) {
                fits[0]++;
                return marked(request, FIT_MARKER);
            }

            @Override
            public ChatCompletionRequest healAfterOverflow(ChatCompletionRequest sent,
                                                           long projectId,
                                                           ServiceException cause) {
                heals[0]++;
                return marked(sent, "\n〔自愈折叠〕");
            }
        };
    }

    private static ChatCompletionRequest marked(ChatCompletionRequest request, String suffix) {
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        ChatMessage last = messages.get(messages.size() - 1);
        messages.set(messages.size() - 1, new ChatMessage(last.role(), last.content() + suffix));
        return new ChatCompletionRequest(request.model(), messages, request.temperature(),
                request.maxTokens(), request.tools(), request.toolChoice(), request.stream(),
                request.streamOptions(), request.promptCacheKey(), request.responseFormat());
    }

    private AiGenServiceImpl serviceWith(LoopContextFitting fitting) {
        return GenWiringHarness.assemble(coreGateway, llmChain, llmRouter, null, null,
                null, fitting);
    }

    private void regenerateScript() {
        serviceWith(markingFitting(new int[1], new int[1])).generate(
                new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 2, "", null, CASE_ID));
    }

    private boolean stage2WasSent() {
        return sentRequests.stream().anyMatch(r ->
                !r.messages().get(0).content().contains("STAGE1_CASE_JSON"));
    }

    @Test
    void stage2ProposalRidesTheBudgetFittingSeam() {
        regenerateScript();

        assertTrue(stage2WasSent(), "stage-2 pass never reached the provider door");
        ChatCompletionRequest stage2 = sentRequests.stream()
                .filter(r -> !r.messages().get(0).content().contains("STAGE1_CASE_JSON"))
                .findFirst().orElseThrow();
        assertTrue(stage2.messages().get(stage2.messages().size() - 1).content()
                        .endsWith(FIT_MARKER),
                "the provider door must receive the FITTED packet, got: "
                        + stage2.messages().get(1).content().substring(
                                Math.max(0, stage2.messages().get(1).content().length() - 80)));
    }

    @Test
    void stage1DirectPassRidesTheBudgetFittingSeam() {
        int[] fits = new int[1];
        int[] heals = new int[1];

        serviceWith(markingFitting(fits, heals)).generate(
                new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                        REQUIREMENT_TEXT, null, null));

        assertTrue(!sentRequests.isEmpty(), "generation never reached the provider door");
        sentRequests.forEach(sent -> assertTrue(
                sent.messages().get(sent.messages().size() - 1).content().contains(FIT_MARKER),
                "every provider call (stage1 direct pass included) must ride the fitting seam"));
        assertEquals(sentRequests.size(), fits[0], "one fit per send, no double folding");
        assertEquals(0, heals[0], "no overflow happened, heal must never fire");
    }

    @Test
    void vendorContextOverflowGetsOneHealedRetryThroughTheSeam() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ServiceException> overflow = new AtomicReference<>();
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    sentRequests.add(r);
                    boolean stage1 = r.messages().get(0).content().contains("STAGE1_CASE_JSON");
                    if (!stage1 && calls.incrementAndGet() == 1) {
                        overflow.set(new ServiceException(
                                "上游模型调用失败（HTTP 400）This is a length "
                                        + "constraint error: context length exceeded",
                                BizErrorCode.AI_PROMPT_TOO_LARGE));
                        throw overflow.get();
                    }
                    return GenWiringHarness.handle(replyFor(r));
                });
        int[] fits = new int[1];
        int[] heals = new int[1];

        serviceWith(markingFitting(fits, heals)).generate(
                new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 2, "", null, CASE_ID));

        assertEquals(1, heals[0], "the overflow safety net must fold exactly once");
        List<ChatCompletionRequest> stage2Sends = sentRequests.stream()
                .filter(r -> !r.messages().get(0).content().contains("STAGE1_CASE_JSON"))
                .toList();
        assertEquals(2, stage2Sends.size(), "rejected call retried once with the healed packet");
        assertTrue(stage2Sends.get(1).messages()
                .get(stage2Sends.get(1).messages().size() - 1).content()
                .contains("〔自愈折叠〕"), "the retry must carry the healed fold");
    }

    @Test
    void legacyAssemblyWithoutFittingKeepsSendAsBuiltBytes() {
        ChatCompletionRequest probe = new ChatCompletionRequest("m",
                List.of(new ChatMessage("user", "问题")), null, null);
        LoopContextFitting none = LoopContextFitting.NONE;

        assertSame(probe, none.fit(probe, PROJECT_ID),
                "NONE identity fitting must never rebuild the packet");
        assertSame(probe, none.fit(probe, PROJECT_ID));
    }

    @Test
    void legacyAssemblyRethrowsTheSameVendorErrorInstanceWithoutHealing() {
        // Old 24-arg assembly (no fitting slot) = NONE posture: a context-overflow
        // 400 is NOT healed into a retry — the very same instance surfaces so the
        // fallback chain keeps owning the failure (byte-identical legacy shape).
        AiGenServiceImpl legacy = new AiGenServiceImpl(llmChain, llmRouter, coreGateway,
                new ObjectMapper(), new CaseJsonParser(new ObjectMapper()),
                new com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator(),
                new com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker(),
                new com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule(),
                new com.sixbean.testpike.common.util.ActionJsonValidator(),
                new com.sixbean.testpike.aiservice.gen.domain.component
                        .ScriptLimitResolver(coreGateway),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        ServiceException overflow = new ServiceException(
                "上游模型调用失败（HTTP 400）context length exceeded",
                BizErrorCode.AI_PROMPT_TOO_LARGE);
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenThrow(overflow);

        ServiceException surfaced = assertThrows(ServiceException.class,
                () -> legacy.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                        2, "", null, CASE_ID)));

        assertSame(overflow, surfaced, "legacy assembly rethrows the exact instance");
        verify(llmChain, times(1)).chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
    }
}
