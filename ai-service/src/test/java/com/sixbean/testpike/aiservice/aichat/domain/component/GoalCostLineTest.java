package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cost-transparency line rules of the goal-plan ledger card (AX batch
 * item "progress cost transparency"): the open-tail sentence gains the
 * session's consumed tokens, read from the SAME ledger door the budget gate
 * and the E9 cost view use (CoreGateway.sessionTotalTokens /
 * ChatUsageAttributionServiceImpl session_total_tokens — R40 single source).
 * <p>Two contracts pin here: the append must keep the frontend fold's pinned
 * prefix shape intact (chatDockReducer.parseGoalOpenTail anchors on
 * main chain owns that surface), and an unreadable consumption (-1) renders
 * an honest "could not read" tail instead of a fabricated zero.
 */
class GoalCostLineTest {

    private static final String CAP_SENTENCE =
            "目标计划还有 2 步未完成（收集需求、生成用例）；自主续跑已达安全上限 3，"
                    + "发送「继续」可手动接续。";
    private static final String BATCH_SENTENCE = "目标计划还有 2 步未完成（收集需求、生成用例），自动接续第 1 批。";

    @Test
    void knownConsumptionAppendsTheCostTailToTheOpenTailSentence() {
        String withCost = GoalCostLine.appendCostTail(CAP_SENTENCE, 12345L);

        assertEquals(CAP_SENTENCE.substring(0, CAP_SENTENCE.length() - 1)
                + "；本会话已消耗 12345 tokens。", withCost);
        // The fold door anchors on the pinned prefix — the append must not touch it.
        assertEquals(true, withCost.startsWith("目标计划还有 2 步未完成（收集需求、生成用例）"));
    }

    @Test
    void unknownConsumptionRendersTheHonestCannotReadTail() {
        String withCost = GoalCostLine.appendCostTail(BATCH_SENTENCE, -1L);

        assertEquals(BATCH_SENTENCE.substring(0, BATCH_SENTENCE.length() - 1)
                + "；本会话消耗量本次未能读取。", withCost);
    }

    @Test
    void absentSentenceStaysAbsent() {
        assertNull(GoalCostLine.appendCostTail(null, 100L));
    }

    @Test
    void progressLineCarriesTokensAndOpenStepCount() {
        assertEquals("本会话已消耗 987 tokens，剩余 4 步未完成。",
                GoalCostLine.progressLine(987L, 4));
        assertEquals("本会话消耗量本次未能读取，剩余 4 步未完成。",
                GoalCostLine.progressLine(-1L, 4));
    }
}
