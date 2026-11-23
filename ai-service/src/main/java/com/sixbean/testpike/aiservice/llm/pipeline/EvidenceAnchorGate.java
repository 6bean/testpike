package com.sixbean.testpike.aiservice.llm.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Built-in verification gate of the behaviour kernel ,
 * adjudication item 1): every conclusion must leave a checkable anchor — an
 * note, or figures backed by this run's tool returns. An assertion without
 * any of those gets one honest reminder at finish time (the caller owns the
 * nudge budget through the door channel and its K8 key); a second
 * finish then passes, and the answer must carry assumption wording marked
 * <p>Pure text rules, no I/O: the same piece serves the live dialog and the
 * batch reasoning domains, so a kernel upgrade lands everywhere at once.
 * Small talk is deliberately silent — only assertive wording or an
 * unsourced quantity claim opens the door, never a bare greeting.
 */
public final class EvidenceAnchorGate {

    /** Anchor family 1: inline grounding citation marker, e.g. [C1]. */
    private static final Pattern CITATION_MARK = Pattern.compile("\\[\\s*C\\s*\\d+\\s*]");

    
    private static final String EVIDENCE_NOTE = "依据：";

    /** Assertive wording that turns a sentence into a claim needing an anchor. */
    private static final List<String> ASSERTIVE_MARKERS =
            List.of("结论", "综上", "因此", "总计", "统计结果", "共 ");

    private EvidenceAnchorGate() {
    }

    /** True when the answer already carries a checkable anchor. */
    public static boolean isAnchored(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return CITATION_MARK.matcher(answer).find() || answer.contains(EVIDENCE_NOTE);
    }

    /**
 * The one finish-time reminder for an unanchored assertion, or null when
 * the answer passes the evidence-first floor. The text names the
 * offending figures when there are any, and both honest exits: attach a
 */
    public static String finishNudgeOrNull(String answer, Collection<String> toolEvidenceTexts) {
        if (answer == null || answer.isBlank() || isAnchored(answer)) {
            return null;
        }
        List<String> unsourced = NumberProvenance.unsourcedQuantities(answer, toolEvidenceTexts);
        boolean evidenceBacked = toolEvidenceTexts != null && !toolEvidenceTexts.isEmpty()
                && unsourced.isEmpty();
        if (!unsourced.isEmpty() && !evidenceBacked) {
            return reminder(String.join("、", unsourced));
        }
        if (unsourced.isEmpty() && evidenceBacked) {
            return null;
        }
        if (hasAssertiveWording(answer)) {
            return reminder("整段无锚断言");
        }
        return null;
    }

    private static boolean hasAssertiveWording(String answer) {
        for (String marker : ASSERTIVE_MARKERS) {
            if (answer.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
 * Draft-level source coverage , signal 3 of the measured
 * confidence): the share of assertive sentences that carry a checkable
 * for sentences making a figure claim, figures that trace back to the
 * evidence texts through the existing {@link NumberProvenance} rule. No
 * new similarity algorithm is introduced here; the gate reuses its own
 * claim and anchor predicates so a kernel upgrade lands in both places.
 *
 * @param draft the generated aggregate text (steps + expected result …)
 * @param evidenceTexts the source blocks the draft was built from
 * @return covered/total over assertive sentences, or null when the draft
 * carries no assertive claim at all — honest absence, never a
 * silent 1.0
 */
    public static Double sourceCoverage(String draft, Collection<String> evidenceTexts) {
        if (draft == null || draft.isBlank()) {
            return null;
        }
        int claims = 0;
        int covered = 0;
        for (String sentence : draft.split("[\\n。；;！!？?]+")) {
            String s = sentence.trim();
            if (s.isEmpty() || !hasAssertiveWording(s)) {
                continue;
            }
            claims++;
            boolean anchored = CITATION_MARK.matcher(s).find() || s.contains(EVIDENCE_NOTE);
            boolean figuresTraceable = s.matches("(?s).*\\d.*")
                    && NumberProvenance.unsourcedQuantities(s, evidenceTexts).isEmpty();
            if (anchored || figuresTraceable) {
                covered++;
            }
        }
        return claims == 0 ? null : (double) covered / claims;
    }

    private static String reminder(String offending) {
        List<String> lines = new ArrayList<>();
        lines.add("收口对账：以下结论缺少可复核锚（[C<n>] 引用、（依据：…）原文锚，"
                + "或本会话工具返回可对上）：" + offending + "。");
        lines.add("请补充真实证据锚后再收口；确实拿不到锚时，改用假设语气并标注「未验证」——"
                + "该标注会留在目标账本上，不得删除。");
        return String.join("\n", lines);
    }
}
