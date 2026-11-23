package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the goal-echo kernel piece : every
 * published template row with a conservative fallback, an answer missing the
 * line gets exactly one finish nudge naming both exits, and a clarify round
 * must never carry the echo line (echo + question in one turn = conflict).
 */
class GoalEchoGateTest {

    private static final String ECHO = "理解为 统计本周失败用例数｜约束 仅当前项目、执行记录表";

    @Test
    void recognizesWellFormedEchoOnFirstLine() {
        assertTrue(GoalEchoGate.firstLineHasEcho(
                ECHO + "\n根据查询结果，本周共 12 条失败。"));
    }

    @Test
    void rejectsMissingOrMalformedEcho() {
        assertFalse(GoalEchoGate.firstLineHasEcho(null));
        assertFalse(GoalEchoGate.firstLineHasEcho(""));
        assertFalse(GoalEchoGate.firstLineHasEcho("本周共 12 条失败。"));
        // constraint slot absent — a bare restatement is not an echo
        assertFalse(GoalEchoGate.firstLineHasEcho("理解为 统计失败数\n正文"));
        // echo buried below the first line — position is part of the contract
        assertFalse(GoalEchoGate.firstLineHasEcho("开场白\n" + ECHO));
    }

    @Test
    void nudgeFiresOnlyForUnEchoedAnswersAndNamesBothExits() {
        assertNull(GoalEchoGate.finishNudgeOrNull(ECHO + "\n正文"));
        assertNull(GoalEchoGate.finishNudgeOrNull(null));
        String nudge = GoalEchoGate.finishNudgeOrNull("本周共 12 条失败。");
        assertNotNull(nudge);
        assertTrue(nudge.contains("理解为"), "nudge carries the echo vocabulary");
        assertTrue(nudge.contains("约束"), "nudge carries the constraint slot");
        assertTrue(nudge.contains("追问"), "nudge states the clarify-round exemption");
    }

    @Test
    void headClausePrefersPublishedRowAndFallsBackConservatively() {
        String published = "操作员措辞：每轮回答第一行写「理解为 X｜约束 Y」。";
        assertEquals(published, GoalEchoGate.headClause(published));
        assertEquals(GoalEchoGate.CLAUSE, GoalEchoGate.headClause(null));
        assertEquals(GoalEchoGate.CLAUSE, GoalEchoGate.headClause("   "));
    }

    @Test
    void gateKeepsEchoOnUnlessExplicitlyClosed() {
        assertTrue(GoalEchoGate.enabled(null));
        assertTrue(GoalEchoGate.enabled("garbage"));
        assertFalse(GoalEchoGate.enabled("0"));
        assertFalse(GoalEchoGate.enabled("false"));
    }

    @Test
    void clarifyRoundWithEchoLineIsDetectedAsConflict() {
        assertFalse(GoalEchoGate.clarifyRoundConflict(null));
        assertFalse(GoalEchoGate.clarifyRoundConflict("问题重述：目标项目指哪个？"));
        assertTrue(GoalEchoGate.clarifyRoundConflict(ECHO + "\n问题重述：目标项目指哪个？"));
    }

    @Test
    void stripLeadingEchoOnlyRemovesTheEchoLine() {
        assertEquals(ECHO, GoalEchoGate.stripLeadingEcho(ECHO));
        assertEquals("问题重述：目标项目指哪个？",
                GoalEchoGate.stripLeadingEcho(ECHO + "\n问题重述：目标项目指哪个？"));
        assertEquals("开场白\n" + ECHO, GoalEchoGate.stripLeadingEcho("开场白\n" + ECHO));
    }
}
