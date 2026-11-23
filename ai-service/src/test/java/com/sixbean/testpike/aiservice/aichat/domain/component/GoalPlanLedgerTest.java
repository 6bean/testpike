package com.sixbean.testpike.aiservice.aichat.domain.component;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Goal-plan ledger rules : a done step only counts as done
 * when it carries an evidence anchor (anti fake-done), an identical replay
 * must be recognised so the store can skip the write (revision rides real
 * changes only), and the pending tail renders into the system section the
 * "continue" turn assembles.
 */
class GoalPlanLedgerTest {

    private static final String FOUR_STATES = """
            [{"title":"查执行记录","state":"done","evidence":"[T:1201]"},\
             {"title":"统计失败率","state":"done","evidence":"[C:3]"},\
             {"title":"生成回归用例","state":"pending","evidence":""},\
             {"title":"通知负责人","state":"blocked","evidence":""}]""";

    @Test
    @DisplayName("done step without an evidence anchor is downgraded to pending")
    void downgradesFakeDoneStep() {
        String input = "[{\"title\":\"A\",\"state\":\"done\",\"evidence\":\"\"},"
                + "{\"title\":\"B\",\"state\":\"done\",\"evidence\":\"  \"},"
                + "{\"title\":\"C\",\"state\":\"done\",\"evidence\":\"[T:1]\"}]";
        String normalized = GoalPlanLedger.normalize(input);
        assertTrue(normalized.contains("\"title\":\"A\""));
        assertTrue(GoalPlanLedger.pendingCount(input) >= 2,
                "two evidence-less done steps must not count as done: " + normalized);
        assertEquals(0, GoalPlanLedger.pendingCount("[{\"title\":\"C\",\"state\":\"done\",\"evidence\":\"[T:1]\"}]"),
                "a step that really carries an evidence anchor stays done");
    }

    @Test
    @DisplayName("pending count follows the normalized states, not the raw ones")
    void pendingCountReflectsNormalizedStates() {
        assertEquals(2, GoalPlanLedger.pendingCount(FOUR_STATES));
        assertEquals(0, GoalPlanLedger.pendingCount(
                "[{\"title\":\"A\",\"state\":\"done\",\"evidence\":\"[T:1]\"}]"));
    }

    @Test
    @DisplayName("sameSet ignores key order and whitespace but catches any real change")
    void sameSetDetectsReplayVersusRealChange() {
        String reordered = "[{\"state\":\"done\",\"evidence\":\"[C:3]\",\"title\":\"统计失败率\"},"
                + "{\"evidence\":\"[T:1201]\",\"state\":\"done\",\"title\":\"查执行记录\"},"
                + "{\"title\":\"生成回归用例\",\"state\":\"pending\",\"evidence\":\"\"},"
                + "{\"title\":\"通知负责人\",\"state\":\"blocked\",\"evidence\":\"\"}]";
        assertTrue(GoalPlanLedger.sameSet(FOUR_STATES, reordered),
                "replayed identical step set (key order aside) must read as no change");
        assertFalse(GoalPlanLedger.sameSet(FOUR_STATES,
                FOUR_STATES.replace("state\":\"pending", "state\":\"skipped")));
        assertFalse(GoalPlanLedger.sameSet(FOUR_STATES,
                FOUR_STATES.replace("[T:1201]", "[T:9999]")));
        assertFalse(GoalPlanLedger.sameSet(FOUR_STATES,
                FOUR_STATES.replace("\"title\":\"通知负责人\"", "\"title\":\"换掉的标题\"")));
    }

    @Test
    @DisplayName("system section names the pending tail for the continue turn")
    void rendersPendingTailForContinuation() {
        String section = GoalPlanLedger.renderSystemSection(FOUR_STATES);
        assertTrue(section.contains("生成回归用例"), section);
        assertTrue(section.contains("通知负责人"), section);
        assertTrue(section.contains("不要重复"), "the block must tell the model to skip verified work: " + section);
        assertTrue(section.contains("[T:1201]"), "done anchors ride along for anti-redo: " + section);
        assertTrue(section.contains("继续"), "must tell the model how to resume: " + section);
        assertTrue(GoalPlanLedger.renderSystemSection(
                "[{\"title\":\"A\",\"state\":\"done\",\"evidence\":\"[T:1]\"}]").isEmpty(),
                "a closed ledger renders nothing");
    }

    @Test
    @DisplayName("unverified note rides normalize, marks only open steps, and survives replays")
    void unverifiedNoteRoundTrip() {
        String noted = GoalPlanLedger.withUnverifiedNote(
                "[{\"title\":\"A\",\"state\":\"pending\",\"evidence\":\"\"},"
                        + "{\"title\":\"B\",\"state\":\"done\",\"evidence\":\"[C:3]\"},"
                        + "{\"title\":\"C\",\"state\":\"skipped\",\"evidence\":\"\"}]");
        assertNotNull(noted);
        assertTrue(noted.contains(GoalPlanLedger.NOTE_UNVERIFIED),
                "未完成步骤必须落未验证标注: " + noted);
        String afterDone = noted.substring(noted.indexOf("B"));
        org.junit.jupiter.api.Assertions.assertFalse(
                afterDone.contains(GoalPlanLedger.NOTE_UNVERIFIED),
                "已完成/已跳过步骤不携带未验证标注");

        String kept = GoalPlanLedger.normalize(
                "[{\"title\":\"A\",\"state\":\"pending\",\"evidence\":\"\",\"note\":\"自定义标注\"}]");
        assertTrue(kept.contains("自定义标注"), "normalize 必须保留 note 字段: " + kept);

        String preserved = GoalPlanLedger.preserveUnverifiedNote(kept,
                "[{\"title\":\"A\",\"state\":\"blocked\",\"evidence\":\"\"}]");
        assertTrue(preserved.contains("自定义标注"),
                "同标题重放不得剥离既有标注（禁删）: " + preserved);
    }

    @Test
    @DisplayName("absent or unparsable ledger stays conservative instead of throwing")
    void unparsableLedgerDegradesConservatively() {
        assertNull(GoalPlanLedger.normalize(null));
        assertNull(GoalPlanLedger.normalize("not json"));
        assertEquals(0, GoalPlanLedger.pendingCount(null));
        assertTrue(GoalPlanLedger.sameSet(null, null));
        assertFalse(GoalPlanLedger.sameSet(null, FOUR_STATES));
        assertEquals("", GoalPlanLedger.renderSystemSection("garbage"));
    }

    @Test
    @DisplayName("the resume trigger word is recognised as the whole prompt, never inside one")
    void resumeCommandIsRecognisedOnlyWhenThePromptIsThatWordAlone() {
        assertTrue(GoalPlanLedger.isResumeCommand("继续"), "the exact word resumes");
        assertTrue(GoalPlanLedger.isResumeCommand(" 继续 "), "surrounding blanks are noise");
        assertFalse(GoalPlanLedger.isResumeCommand("继续做第一批用例"),
                "a fuller sentence is a new question, not a resume");
        assertFalse(GoalPlanLedger.isResumeCommand(null));
        assertFalse(GoalPlanLedger.isResumeCommand("  "));
    }

    @Test
    @DisplayName("open titles list the pending and blocked steps in order")
    void openTitlesListThePendingAndBlockedStepsInOrder() {
        String json = "[{\"title\":\"收集需求\",\"state\":\"pending\",\"evidence\":\"\"},"
                + "{\"title\":\"生成用例\",\"state\":\"blocked\",\"evidence\":\"\"},"
                + "{\"title\":\"归档\",\"state\":\"done\",\"evidence\":\"tool-1\"}]";
        assertEquals(java.util.List.of("收集需求", "生成用例"), GoalPlanLedger.openTitles(json));
        assertEquals(java.util.List.of(), GoalPlanLedger.openTitles(null));
        assertEquals(java.util.List.of(), GoalPlanLedger.openTitles("not-json"));
    }
}
