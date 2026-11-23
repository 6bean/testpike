package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmOutputCapabilityLedger;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Stage-2 script channel over the format-residual stop-loss family (the same
 * V615 machine gate the case channel already rides, closed on its three
 * proven gaps): (a) the format-class word family recognises the script-side
 * dispute prefixes, so a shape violation on the action-JSON channel is not
 * mistaken for a semantic dispute; (b) a ring that exhausts its rounds on a
 * format-class residual ends with one budgeted targeted re-fill instead of
 * the legacy whole-order FORMAT_ERROR; (c) the accept-but-not-enforced proof
 * on the script wire corrects the capability ledger row (form schema_only),
 * which stays blind without it. Budget off (K8 = 0) restores the legacy
 * rejection honestly — the repair never rides the wire.
 * <p>L2 stance (shared with the sibling suites): infrastructure mocks only,
 * every judgement component (validator, ring, style gate) real.
 */
class AiGenServiceImplStage2FormatResidualRepairTest {

    private static final long TASK_ID = 66502L;
    private static final long PROJECT_ID = 201052L;
    private static final long OPERATOR_ID = 12L;
    private static final long NEW_CASE_ID = 930002L;

    /** Discriminates the stage-2 targeted re-fill from a ring pass on the wire. */
    private static final String STAGE2_REPAIR_MARK = "脚本数据修复器";

    /** Stage-1 answer that converges, so the flow reaches the script channel. */
    private static final String CLEAN_CASE = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /**
     * Shape-breaking script (the live signature family: goto without url).
     * Otherwise mirrors the clean script so the violations that fire are
     * format-class only, not posture-class.
     */
    private static final String STAGE2_BROKEN = """
            {"version":"v2","caseId":"TC-fmt-66502","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页"}],
             "steps":[
               {"action":"wait","description":"等待登录表单出现","target":{"css":"#username","chain":["css"]},"state":"visible"},
               {"action":"expect","description":"断言跳转首页","target":{"text":"首页"},"value":"首页"}]}""";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain chain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter router = GenWiringHarness.mockLlmRouter();
    private final LlmOutputCapabilityLedger ledger = mock(LlmOutputCapabilityLedger.class);

    private final List<ChatCompletionRequest> stage2RingCalls = new ArrayList<>();
    private final List<ChatCompletionRequest> stage2RepairCalls = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean repairAnswersClean =
            new java.util.concurrent.atomic.AtomicBoolean(true);

    /** Route every wire pass by its system face, scripted repair answer. */
    private void stubRouting(String repairRoundsRaw) {
        when(router.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "fmt-repair", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-fmt-1"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                AiGenServiceImpl.FORMAT_REPAIR_ROUNDS_KEY.equals(inv.getArgument(0))
                        ? repairRoundsRaw : null);
        when(chain.chatHandle(any(), eq(PROJECT_ID), any())).thenAnswer(inv -> {
            ChatCompletionRequest r = inv.getArgument(0);
            String system = r.messages().get(0).content();
            if (system.contains(STAGE2_REPAIR_MARK)) {
                stage2RepairCalls.add(r);
                return GenWiringHarness.handle(
                        repairAnswersClean.get() ? GenWiringHarness.STAGE2_CLEAN : STAGE2_BROKEN);
            }
            if (system.contains("STAGE1_CASE_JSON")) {
                return GenWiringHarness.handle(CLEAN_CASE);
            }
            stage2RingCalls.add(r);
            return GenWiringHarness.handle(STAGE2_BROKEN);
        });
    }

    private AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null);
    }

    private AiGenServiceImpl service() {
        return GenWiringHarness.assembleWithCapabilityLedger(coreGateway, chain, router, ledger);
    }

    @Test
    void 词族判据_脚本形态违例属于格式类_姿势类与空集不属于() {
        assertTrue(GenStructuredOutput.hasFormatResidual(
                        List.of("上次脚本不合格：goto 动作缺少必填项 url")),
                "the script-shape prefix is a format-class dispute");
        assertTrue(GenStructuredOutput.hasFormatResidual(
                        List.of("落库被脚本静态校验拒：net.assert 缺少 match.urlPattern")),
                "the save-door prefix is a format-class dispute");
        assertFalse(GenStructuredOutput.hasFormatResidual(
                        List.of("上次脚本姿势不符合团队规范：定位表达式缺 chain")),
                "a posture dispute is semantic, never enforcement evidence");
        assertTrue(GenStructuredOutput.hasFormatResidual(
                        List.of("上次输出格式不合格：缺少 title")),
                "the legacy case-channel prefixes keep their meaning");
        assertFalse(GenStructuredOutput.hasFormatResidual(null));
    }

    @Test
    void 脚本通道形态残余_能力台账记schema_only未强制观察() {
        stubRouting(null);
        service().generate(message());
        verify(ledger, times(1)).observeNotEnforced(
                eq(GenWiringHarness.ENDPOINT.modelName()),
                eq(LlmOutputCapabilityLedger.FORM_SCHEMA_ONLY));
        verify(ledger, never()).observeNotEnforced(
                eq(GenWiringHarness.ENDPOINT.modelName()),
                eq(LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA));
    }

    @Test
    void 环用尽格式类残余_一轮预算制定向补写救回落库() {
        stubRouting(null);
        service().generate(message());
        assertEquals(1, stage2RepairCalls.size(),
                "exactly one budgeted targeted re-fill after the ring");
        assertNotNullMountedSchema();
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), saved.capture(), eq(1),
                eq(OPERATOR_ID));
        assertTrue(new ActionJsonValidator().validate(saved.getValue()).passed(),
                "what reaches the save door passes the same validator: " + saved.getValue());
        verify(coreGateway).markDone(eq(TASK_ID), eq(NEW_CASE_ID));
        verify(coreGateway, never()).markFailed(eq(TASK_ID), eq("FORMAT_ERROR"), any());
    }

    private void assertNotNullMountedSchema() {
        ChatCompletionRequest ride = stage2RepairCalls.get(0);
        org.junit.jupiter.api.Assertions.assertNotNull(ride.responseFormat(),
                "the re-fill rides the schema-bearing enforceable form");
    }

    @Test
    void 修复预算关闭_旧硬失败形态文案与业务码零漂移() {
        stubRouting("0");
        ServiceException hardFail = assertThrows(ServiceException.class,
                () -> service().generate(message()));
        assertTrue(hardFail.getMessage().contains("推理确认环"), hardFail.getMessage());
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, hardFail.getCode());
        assertEquals(0, stage2RepairCalls.size(), "budget 0 = the repair never rides the wire");
    }

    @Test
    void 定向补写仍不合契约_保留原判不假绿() {
        repairAnswersClean.set(false);
        stubRouting(null);
        ServiceException hardFail = assertThrows(ServiceException.class,
                () -> service().generate(message()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, hardFail.getCode(), hardFail.getMessage());
        assertEquals(1, stage2RepairCalls.size(),
                "budget respected: one re-fill round, never spinning");
        verify(coreGateway).markFailed(eq(TASK_ID), eq("FORMAT_ERROR"), any());
    }
}
