package com.sixbean.testpike.aiservice.aichat.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.tool.domain.component.ClarifyTool;
import com.sixbean.testpike.aiservice.tool.domain.component.FrameLedgerStreamSink;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Goal-plan finish door : the reconciler turns the
 * plan.propose call of the current turn into the session ledger and answers
 * the runner's plain-text finish with the resume block — exactly
 * {@code ai.chat.goal-reconcile-nudges} times per run (factory 1; key value
 * 0 honestly closes the nudge, a garbage value falls back to 1). A turn that
 * never proposed a plan is left untouched (no cross-turn nagging), garbage
 * proposal bodies write nothing, and the second consult after a nudge lets
 * the finish through with zero further store traffic. The dialog sink seam
 * is covered here too: only the dialog wiring exposes a door, every other
 * surface keeps the legacy null answer.
 */
class GoalPlanReconcilerTest {

    private static final long SESSION_ID = 71L;
    private static final int PLAN_VERSION = 1;

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GoalPlanLedgerStore store = new GoalPlanLedgerStore(jdbc);
    private final CoreGateway gateway = mock(CoreGateway.class);

    private GoalPlanReconciler underTest() {
        return new GoalPlanReconciler(SESSION_ID, PLAN_VERSION, store, gateway);
    }

    private void stubNoStoredLedger() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of());
    }

    private static List<ChatMessage> proposedTurn(String stepsArgsJson) {
        return List.of(
                new ChatMessage("user", "帮我把用例补齐"),
                ChatMessage.assistantWithToolCalls("我先给个计划",
                        List.of(new LlmToolCall("call-1", "plan.propose", stepsArgsJson))),
                ChatMessage.tool("call-1", "计划已记录"),
                new ChatMessage("assistant", "以上就是我的安排"));
    }

    private static final String TWO_STEP_ARGS =
            "{\"steps\":[\"收集需求\",\"生成用例\"]}";

    @Test
    void proposedPlanWithOpenStepsNudgesOnceThenFinishPasses() {
        stubNoStoredLedger();
        GoalPlanReconciler door = underTest();

        String nudge = door.takeFinishNudge(proposedTurn(TWO_STEP_ARGS));

        assertNotNull(nudge, "本轮提出了计划且账上仍有未完成步骤，首次拟收口必须收到提醒");
        assertTrue(nudge.contains("收集需求") && nudge.contains("生成用例"),
                "提醒必须点名未完成步骤，让模型知道该接续哪一步");
        assertTrue(nudge.contains("目标账本"), "提醒以目标账本块注入，语义与续跑轮一致");
    }

    @Test
    void nudgePersistsLedgerOnceAndSecondConsultPassesWithZeroWrites() {
        stubNoStoredLedger();
        GoalPlanReconciler door = underTest();

        assertNotNull(door.takeFinishNudge(proposedTurn(TWO_STEP_ARGS)));
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));

        assertNull(door.takeFinishNudge(proposedTurn(TWO_STEP_ARGS)),
                "预算（缺省 1）耗尽后第二次拟收口必须放行");
        verify(jdbc, times(1)).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void zeroBudgetKeyClosesTheNudgeWithoutTouchingTheLedger() {
        when(gateway.getSysConfig(GoalPlanReconciler.KEY_NUDGE_BUDGET)).thenReturn("0");
        GoalPlanReconciler door = underTest();

        assertNull(door.takeFinishNudge(proposedTurn(TWO_STEP_ARGS)),
                "键值 0 = 用户显式关闭提醒，不得再有任何注入或落账");

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void garbageBudgetValueFallsBackToOneNudge() {
        when(gateway.getSysConfig(GoalPlanReconciler.KEY_NUDGE_BUDGET)).thenReturn("abc");
        stubNoStoredLedger();
        GoalPlanReconciler door = underTest();

        assertNotNull(door.takeFinishNudge(proposedTurn(TWO_STEP_ARGS)),
                "非法值回退缺省 1 次，配置写坏不能让目标半途失联");
        assertNull(door.takeFinishNudge(proposedTurn(TWO_STEP_ARGS)));
    }

    @Test
    void turnWithoutPlanProposalNeverNudgesOrWrites() {
        GoalPlanReconciler door = underTest();
        when(gateway.getSysConfig("ai.chat.goal-echo.enabled")).thenReturn("0");
        List<ChatMessage> plainTurn = List.of(
                new ChatMessage("user", "今天天气如何"),
                new ChatMessage("assistant", "我不掌握实时天气"));

        assertNull(door.takeFinishNudge(plainTurn),
                "本轮未提出计划就不许提醒——跨轮追着用户不放是噪音不是续跑");
        verifyNoInteractions(jdbc);
    }

    @Test
    void unparsableProposalBodyLandsNothingAndStaysSilent() {
        GoalPlanReconciler door = underTest();
        when(gateway.getSysConfig("ai.chat.goal-echo.enabled")).thenReturn("0");

        assertNull(door.takeFinishNudge(proposedTurn("{\"steps\":\"不是数组\"")));
        verifyNoInteractions(jdbc);
    }

    @Test
    void unanchoredAssertionWithoutAnyPlanGetsOneEvidenceNudgeAndStaysOffTheLedger() {
        GoalPlanReconciler door = underTest();
        List<ChatMessage> assertiveTurn = List.of(
                new ChatMessage("user", "统计一下用例数量"),
                new ChatMessage("assistant", "综上，共有 42 条用例"));

        String nudge = door.takeFinishNudge(assertiveTurn);
        assertNotNull(nudge, "无锚断言收口必须收到验证段的恰一次提醒（T526 切片4）");
        assertTrue(nudge.contains("42") && nudge.contains("未验证"),
                "提醒必须点名缺据数字并给出两出口（补锚或假设语气标注未验证）: " + nudge);

        assertNull(door.takeFinishNudge(assertiveTurn),
                "额度（与目标续跑共用 ai.chat.goal-reconcile-nudges）耗尽后第二次拟收口放行");
        verifyNoInteractions(jdbc);
    }

    @Test
    void anchoredFinishNeverConsumesTheDoor() {
        GoalPlanReconciler door = underTest();
        when(gateway.getSysConfig("ai.chat.goal-echo.enabled")).thenReturn("0");
        List<ChatMessage> anchored = List.of(
                new ChatMessage("user", "统计一下用例数量"),
                new ChatMessage("assistant", "综上，共有 42 条用例 [C1]"));

        assertNull(door.takeFinishNudge(anchored), "带 [C<n>] 引用锚的回答是合格收口，不得打扰");
        assertNull(door.takeFinishNudge(anchored));
        verifyNoInteractions(jdbc);
    }

    @Test
    void echoLaneFiresOnceOnUnEchoedFinishAndSharesTheNudgeBudget() {
        GoalPlanReconciler door = underTest();
        // Factory-on gate (no stub = missing row = on): an evidence-clean
        // answer that opens without the goal-echo line rides the echo lane
        // exactly once, spending the shared per-run nudge budget.
        List<ChatMessage> unEchoed = List.of(
                new ChatMessage("user", "统计一下用例数量"),
                new ChatMessage("assistant", "综上，共有 42 条用例 [C1]"));

        String nudge = door.takeFinishNudge(unEchoed);
        assertNotNull(nudge, "无回显行的引用合格回答仍属目标回显未达标");
        assertTrue(nudge.contains("理解为"), "nudge carries the echo format");
        assertNull(door.takeFinishNudge(unEchoed), "echo lane shares the goal-reconcile budget");
        verifyNoInteractions(jdbc);
    }

    @Test
    void echoedFinishPassesTheEchoLaneUntouched() {
        GoalPlanReconciler door = underTest();
        List<ChatMessage> echoed = List.of(
                new ChatMessage("user", "统计一下用例数量"),
                new ChatMessage("assistant",
                        "理解为 统计用例数量｜约束 仅当前项目\n综上，共有 42 条用例 [C1]"));

        assertNull(door.takeFinishNudge(echoed), "首行回显在位时回显通道零打扰");
        verifyNoInteractions(jdbc);
    }

    @Test
    void budgetSpentAfterGoalNudgeMarksUnverifiedOnTheLedgerOnce() {
        stubNoStoredLedger();
        GoalPlanReconciler door = underTest();
        List<ChatMessage> unanchoredFinish = List.of(
                new ChatMessage("user", "帮我把用例补齐"),
                ChatMessage.assistantWithToolCalls("我先给个计划",
                        List.of(new LlmToolCall("call-1", "plan.propose", TWO_STEP_ARGS))),
                ChatMessage.tool("call-1", "计划已记录"),
                new ChatMessage("assistant", "综上，共有 42 条用例已生成"));

        assertNotNull(door.takeFinishNudge(unanchoredFinish), "首轮先走目标续跑提醒");

        assertNull(door.takeFinishNudge(unanchoredFinish),
                "额度耗尽后放行，但无锚结论必须在账本落『未验证』标注");
        assertNull(door.takeFinishNudge(unanchoredFinish), "第三次咨询不得重复落账（恰一次标注，R28）");

        var captor = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), captor.capture());
        String noted = String.valueOf(captor.getAllValues().get(1).getValue("steps"));
        assertTrue(noted.contains("未验证"), "第二次落账的账本必须带未验证标注: " + noted);
        verify(gateway, times(3)).getSysConfig(GoalPlanReconciler.KEY_NUDGE_BUDGET);
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void dialogSinkExposesTheWiredDoorAndPlainSinksKeepLegacyNull() {
        GoalPlanReconciler door = underTest();
        FrameLedgerStreamSink wired = new FrameLedgerStreamSink(gateway, SESSION_ID, 99L,
                null, door);
        assertSame(door, wired.finishReconciler(),
                "对话面必须把门透传给 ReAct 循环收口支路");

        FrameLedgerStreamSink plain = new FrameLedgerStreamSink(gateway, SESSION_ID, 99L);
        assertNull(plain.finishReconciler(),
                "未接门的旧构造逐字节保持遗留收口行为");
    }

    // Clarify-suspension lane : a run that ends on an
    // accepted clarify card never reaches the finish door, so this lane is
    // the only place its proposed plan can reach the ledger — reload and the
    // next "continue" turn read the card back from there.

    private static List<ChatMessage> suspendedTurn(String stepsArgsJson) {
        return List.of(
                new ChatMessage("user", "帮我把用例补齐"),
                ChatMessage.assistantWithToolCalls("先给计划，再确认一点",
                        List.of(new LlmToolCall("call-1", "plan.propose", stepsArgsJson),
                                new LlmToolCall("call-2", ClarifyTool.NAME,
                                        "{\"question\":\"给哪个项目？\",\"options\":[\"甲\",\"乙\"]}"))),
                ChatMessage.tool("call-1", "计划已记录"),
                ChatMessage.tool("call-2", "已挂起等待用户确认"));
    }

    @Test
    void clarifySuspensionPersistsLedgerWithoutSpendingNudgeBudget() {
        stubNoStoredLedger();
        GoalPlanReconciler door = underTest();

        door.onRunSuspend(suspendedTurn(TWO_STEP_ARGS));

        verify(jdbc, times(1)).update(anyString(), any(MapSqlParameterSource.class));
        assertNotNull(door.takeFinishNudge(suspendedTurn(TWO_STEP_ARGS)),
                "挂起通道落账但不耗提醒额度——随后正常拟收口仍应收到提醒");
    }

    @Test
    void clarifySuspensionWithoutAnyProposalWritesNothing() {
        GoalPlanReconciler door = underTest();
        List<ChatMessage> plain = List.of(
                new ChatMessage("user", "今天天气如何"),
                new ChatMessage("assistant", "我需要确认一点：哪个城市？"));

        door.onRunSuspend(plain);

        verifyNoInteractions(jdbc);
    }

    @Test
    void zeroBudgetKeyClosesTheSuspensionWriteToo() {
        when(gateway.getSysConfig(GoalPlanReconciler.KEY_NUDGE_BUDGET)).thenReturn("0");
        GoalPlanReconciler door = underTest();

        door.onRunSuspend(suspendedTurn(TWO_STEP_ARGS));

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }
}
