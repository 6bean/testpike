package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Verdict ledger of one rule-item reconciliation pass .
 *
 * @param verdicts per table-row item outcomes in extraction order;
 * empty when nothing was reconciled or the embedding
 * chain degraded (never fabricated verdicts)
 * @param proseCandidateCount prose lines booked for human adjudication — they
 * are counted, never auto-judged
 * @param embeddingDegraded the embedding chain failed, so the legacy
 * judgement holds untouched; the caller names a WARN
 */
public record RuleLedgerOutcome(List<RuleLedgerVerdict> verdicts,
                                int proseCandidateCount,
                                boolean embeddingDegraded) {

    /** Uncovered table-row items, extraction order. */
    public List<RuleLedgerVerdict> missing() {
        List<RuleLedgerVerdict> out = new ArrayList<>();
        for (RuleLedgerVerdict verdict : verdicts) {
            if (!verdict.covered()) {
                out.add(verdict);
            }
        }
        return out;
    }

    public int missingCount() {
        return verdicts.size() - coveredCount();
    }

    public int coveredCount() {
        int covered = 0;
        for (RuleLedgerVerdict verdict : verdicts) {
            if (verdict.covered()) {
                covered++;
            }
        }
        return covered;
    }

    /**
 * Feedback lines for the existing repair-ring slot :
 * each names the rule content and its source chunk, and states the fix
 * (add the matching operation and assertion steps). Degrades to empty
 * when nothing is missing or the chain failed — the legacy judgement
 * then rides the ring alone.
 */
    public List<String> feedbackLines() {
        List<String> lines = new ArrayList<>();
        for (RuleLedgerVerdict verdict : missing()) {
            RuleItem item = verdict.item();
            lines.add("规则条目未覆盖（来源块 #" + item.chunkId() + "）："
                    + item.criterionText()
                    + " —— 请为该规则补充对应的操作步骤与断言步骤");
        }
        return lines;
    }
}
