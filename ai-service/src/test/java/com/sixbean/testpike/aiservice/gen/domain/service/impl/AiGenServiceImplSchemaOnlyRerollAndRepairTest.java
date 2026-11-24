package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmOutputCapabilityLedger;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V615 stop-loss wiring over the real ring: a model output that keeps
 * violating the schema contract must (a) record the accept-but-not-enforce
 * observation against the form that carried the mounted block (the machine
 * gate "accepted without enforced -> correct the capability row"), (b) keep
 * every schema-bearing revision with the block mounted (the fix steers the
 * wire FORM, never drops the schema), and (c) end on a targeted field fill
 * saving the case — not on the legacy whole-task FORMAT_ERROR. The repair-off
 * hot key must restore the exact legacy failure honestly.
 * <p>L2 stance (shared with the sibling suites): infrastructure mocks only,
 * every judgement component (parser, self-check, ring, gate) real.
 */
class AiGenServiceImplSchemaOnlyRerollAndRepairTest {

    private static final long TASK_ID = 66401L;
    private static final long PROJECT_ID = 201037L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 930001L;

    /** Discriminates the targeted-fill pass from a stage1 ring pass on the wire. */
    private static final String REPAIR_SYSTEM_MARK = "数据修复器";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain chain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter router = GenWiringHarness.mockLlmRouter();
    private final LlmOutputCapabilityLedger ledger = mock(LlmOutputCapabilityLedger.class);

    /** Shape-breaking draft: required title absent (the live 09:46 signature). */
    private static final String SHAPE_BREAKING = """
            {"precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0}],
             "expectedResult":"跳转首页"}""";

    /** Valid shape (used as the repair answer and the stage2 clean script). */
    private static final String STAGE1_CLEAN = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    private final List<ChatCompletionRequest> stage1Calls = new ArrayList<>();
    private final List<ChatCompletionRequest> repairCalls = new ArrayList<>();

    /**
     * Answer every wire pass by its system face: stage1 ring passes consume
     * the scripted (always broken) drafts, the repair pass answers with a
     * valid case, anything else is the clean stage2 script.
     */
    private void stubRouting(String repairRoundsRaw) {
        when(router.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "v615", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-v615-1"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                AiGenServiceImpl.FORMAT_REPAIR_ROUNDS_KEY.equals(inv.getArgument(0))
                        ? repairRoundsRaw : null);
        when(chain.chatHandle(any(), eq(PROJECT_ID), any())).thenAnswer(inv -> {
            ChatCompletionRequest r = inv.getArgument(0);
            String system = r.messages().get(0).content();
            if (system.contains(REPAIR_SYSTEM_MARK)) {
                repairCalls.add(r);
                return GenWiringHarness.handle(repairAnswersClean.get() ? STAGE1_CLEAN : SHAPE_BREAKING);
            }
            if (system.contains("STAGE1_CASE_JSON")) {
                stage1Calls.add(r);
                return GenWiringHarness.handle(SHAPE_BREAKING);
            }
            return GenWiringHarness.handle(GenWiringHarness.STAGE2_CLEAN);
        });
    }

    private final java.util.concurrent.atomic.AtomicBoolean repairAnswersClean =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    private AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null);
    }

    private AiGenServiceImpl service() {
        return GenWiringHarness.assembleWithCapabilityLedger(coreGateway, chain, router, ledger);
    }

    @Test
    void 格式类残余_记录能力观察_重生成保持schema挂载_定向补写救回整单() {
        stubRouting(null);
        service().generate(message());

        assertFalse(stage1Calls.isEmpty(), "stage1 must have ridden the wire");
        assertTrue(stage1Calls.stream().allMatch(r -> r.responseFormat() != null),
                "every schema-bearing stage1 pass keeps the block mounted (the fix steers its FORM, never drops the schema)");
        assertEquals(1, repairCalls.size(), "exactly one budgeted targeted-fill round after the ring");
        assertNotNull(repairCalls.get(0).responseFormat(),
                "the repair ride mounts the schema over the enforceable tool-free form");
        verify(ledger, times(1)).observeNotEnforced(
                eq(GenWiringHarness.ENDPOINT.modelName()),
                eq(LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA));
        verify(coreGateway).markDone(eq(TASK_ID), eq(NEW_CASE_ID));
        verify(coreGateway, never()).markFailed(eq(TASK_ID), eq("FORMAT_ERROR"), any());
    }

    @Test
    void 修复预算关闭_恢复旧硬失败形态_文案与业务码零漂移() {
        stubRouting("0");
        ServiceException hardFail = assertThrows(ServiceException.class,
                () -> service().generate(message()));
        assertTrue(hardFail.getMessage().startsWith("AI 输出格式校验失败（重试 "),
                hardFail.getMessage());
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, hardFail.getCode());
        assertEquals(0, repairCalls.size(), "budget 0 = the repair never rides the wire");
    }

    @Test
    void 定向补写仍不合契约_保留原判不假绿() {
        repairAnswersClean.set(false);
        stubRouting(null);
        ServiceException hardFail = assertThrows(ServiceException.class,
                () -> service().generate(message()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, hardFail.getCode(), hardFail.getMessage());
        assertEquals(1, repairCalls.size(), "budget respected: one repair round, never spinning");
        verify(coreGateway).markFailed(eq(TASK_ID), eq("FORMAT_ERROR"), any());
    }

    @Test
    void 形态规则真值表_只有挂载schema时才改形() {
        assertTrue(GenStructuredOutput.preferSchemaOnlyForm(true, true, false));
        assertTrue(GenStructuredOutput.preferSchemaOnlyForm(true, false, true));
        assertTrue(GenStructuredOutput.preferSchemaOnlyForm(true, true, true));
        // No schema on the wire: nothing to enforce, the legacy FC shape stays.
        assertFalse(GenStructuredOutput.preferSchemaOnlyForm(false, true, true),
                "gate-off must never silently change the request shape");
        assertFalse(GenStructuredOutput.preferSchemaOnlyForm(false, false, false));
    }
}
