package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal-lifecycle attribution gate (behaviour kernel round-2 item 1): every
 * final answer self-reports whether it continues the session's open goal or
 * opens a new one, and the ledger guardrail is driven by that report —
 * nothing (fail-safe: losing continuity is preferred over letting a stale
 * ledger pollute an unrelated answer).
 */
class GoalAttributionGateTest {

    @Test
    void continueFrameParsesToContinueOld() {
        String answer = "【目标归属】续旧\n理解为 梳理回归计划｜约束 无\n正文内容";
        assertEquals(GoalAttributionFrame.CONTINUE_OLD, GoalAttributionGate.parse(answer));
    }

    @Test
    void openNewFrameParsesToOpenNewEvenWithReasonText() {
        String answer = "理解为 换话题｜约束 无\n【目标归属】开新：先查一下设备清单\n正文";
        assertEquals(GoalAttributionFrame.OPEN_NEW, GoalAttributionGate.parse(answer));
    }

    @Test
    void missingOrBlankAnswerParsesToFrameMissing() {
        assertEquals(GoalAttributionFrame.FRAME_MISSING, GoalAttributionGate.parse("答：好的"));
        assertEquals(GoalAttributionFrame.FRAME_MISSING, GoalAttributionGate.parse(""));
        assertEquals(GoalAttributionFrame.FRAME_MISSING, GoalAttributionGate.parse(null));
    }

    @Test
    void proseMentioningTheWordsWithoutTheFrameLineStaysMissing() {
        // shape must not flip the verdict — the frame is line-anchored.
        String answer = "我们可以续旧的思路讨论，也可以开新一条支线。";
        assertEquals(GoalAttributionFrame.FRAME_MISSING, GoalAttributionGate.parse(answer));
    }

    @Test
    void gateIsFactoryOnAndClosesOnlyOnExplicitOff() {
        assertTrue(GoalAttributionGate.enabled(null));
        assertTrue(GoalAttributionGate.enabled("true"));
        assertTrue(GoalAttributionGate.enabled("garbage"));
        assertFalse(GoalAttributionGate.enabled("false"));
        assertFalse(GoalAttributionGate.enabled("0"));
    }

    @Test
    void closureSkipsOpenStepsWithTraceAndKeepsDoneSteps() {
        String steps = "[{\"title\":\"收集需求\",\"state\":\"done\",\"evidence\":\"tool-call-7\"},"
                + "{\"title\":\"生成用例\",\"state\":\"pending\",\"evidence\":\"\"},"
                + "{\"title\":\"跑冒烟\",\"state\":\"blocked\",\"evidence\":\"\"}]";
        String closed = GoalPlanLedger.withNewGoalClosure(steps);
        assertEquals(0, GoalPlanLedger.pendingCount(closed));
        assertTrue(GoalPlanLedger.openTitles(closed).isEmpty());
        assertTrue(closed.contains("tool-call-7"), "done anchor kept untouched");
        assertTrue(closed.contains(GoalPlanLedger.NOTE_NEW_GOAL_CLOSURE));
        // Closing twice is a no-op (replay discipline: no double stamping).
        assertEquals(GoalPlanLedger.normalize(closed),
                GoalPlanLedger.normalize(GoalPlanLedger.withNewGoalClosure(closed)));
    }

    @Test
    void closureOfNothingOpenReturnsTheNormalizedLedgerUnchanged() {
        String done = "[{\"title\":\"收集需求\",\"state\":\"done\",\"evidence\":\"a\"}]";
        assertEquals(GoalPlanLedger.normalize(done), GoalPlanLedger.withNewGoalClosure(done));
    }

    @Test
    void clauseCarriesBothFrameWordsForTheModel() {
        assertTrue(GoalAttributionGate.CLAUSE.contains("【目标归属】"));
        assertTrue(GoalAttributionGate.CLAUSE.contains("续旧"));
        assertTrue(GoalAttributionGate.CLAUSE.contains("开新"));
    }
}
