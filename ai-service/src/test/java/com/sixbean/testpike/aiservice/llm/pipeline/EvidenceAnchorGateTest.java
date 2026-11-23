package com.sixbean.testpike.aiservice.llm.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 two-way fixtures for the built-in verification gate ,
 * adjudication item 1): an anchored answer passes untouched, an assertion
 * without any checkable anchor is named exactly once through the reminder
 * text, and the reminder itself points at the two honest exits (attach an
 * anchor, or restate as an assumption marked unverified).
 */
class EvidenceAnchorGateTest {

    @Test
    void citationAnchoredAnswerFinishesUntouched() {
        String answer = "结论：登录链路按 [C1] 文档配置，超时项见 [C2]。";
        assertNull(EvidenceAnchorGate.finishNudgeOrNull(answer, List.of()),
                "an answer leaning on [C<n>] citations is already anchored");
    }

    @Test
    void evidenceNoteAnchoredAnswerFinishesUntouched() {
        String answer = "共 128 条用例（依据：tp_case 查询返回）";
        assertNull(EvidenceAnchorGate.finishNudgeOrNull(answer, List.of("用例总数 128 条")),
                "ledger-style （依据：…） markers count as checkable anchors");
    }

    @Test
    void unanchoredAssertiveAnswerGetsOneReminder() {
        String answer = "综上，共 300 条用例覆盖全部需求。";
        String nudge = EvidenceAnchorGate.finishNudgeOrNull(answer, List.of());
        assertNotNull(nudge, "an asserted count with no anchor and no tool evidence must be named");
        assertTrue(nudge.contains("300"), "the reminder must quote the offending figure: " + nudge);
        assertTrue(nudge.contains("未验证"), "the reminder must name the honest fallback marking: " + nudge);
    }

    @Test
    void unanchoredAssertiveSentenceWithoutFiguresStillReminds() {
        String nudge = EvidenceAnchorGate.finishNudgeOrNull(
                "结论：该缺陷已在全部环境修复。", List.of());
        assertNotNull(nudge, "assertive wording without any anchor is a no-anchor assertion too");
        assertTrue(nudge.contains("锚"), "reminder must ask for a checkable anchor: " + nudge);
    }

    @Test
    void plainConversationalAnswerWithoutClaimsIsSilent() {
        assertNull(EvidenceAnchorGate.finishNudgeOrNull(
                "好的，我先帮你梳理一下思路，随时可以继续。", List.of()),
                "the door must not chase small talk — no assertive marker, no quantity claim");
    }

    @Test
    void sourcedFiguresStillNeedNoAnchorReminder() {
        String answer = "统计结果：共 128 条用例，通过率 92.5%。";
        String nudge = EvidenceAnchorGate.finishNudgeOrNull(
                answer, List.of("用例总数 128 条；通过 118 条，通过率 92.5%"));
        assertNull(nudge, "numbers proven by tool returns satisfy the evidence-first clause");
    }
}
