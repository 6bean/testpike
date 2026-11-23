package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.common.log.LogCollapseWindow;
import org.slf4j.Logger;
import org.slf4j.helpers.MessageFormatter;

/**
 * Single-sourced WARN emission for the LLM degradation log families through
 * the shared {@link LogCollapseWindow} ai-service side wiring;
 * the suppression mechanism itself lives in common/log, R40: this package is
 * its second/third consumer, there is no per-service repeat filter).
 * <p>Behaviour: the window-open occurrence prints the full-detail line; a
 * repeat of the same fingerprint inside the window prints nothing; the first
 * hit after the window prints the line plus the suppressed count and the
 * caller's ledger pointer note (R53 — collapsing never silently swallows, the
 * authoritative record always stays reachable). A resolved window of <=0
 * disables collapsing so every line prints (hot-tunable property semantics).
 */
final class LlmDegradationLogCollapse {

    private LlmDegradationLogCollapse() {
    }

    /**
     * Admit one occurrence of a degradation family and log it if the window says so.
     *
     * @param window       owning component's collapse window (never null)
     * @param sink         logger receiving the admitted line (constructor-injected
     *                     so L2 suites capture emissions without a logback appender)
     * @param family       fingerprint family prefix (e.g. {@code llm-retry-exhausted})
     * @param ledgerNote   authoritative-ledger pointer appended to the rollover line
     * @param lineFormat   slf4j format of the full-detail line
     * @param lineArgs     format arguments (never dumped verbatim into logs beyond
     *                     this single line; api keys must not be among them)
     */
    static void admitAndLog(LogCollapseWindow window, Logger sink, String family, String ledgerNote,
                            String lineFormat, Object... lineArgs) {
        // Eager formatting is deliberate: this call site emits at most one line per
        // window per fingerprint, and isWarnEnabled() is always true on the sink.
        String detail = MessageFormatter.arrayFormat(lineFormat, lineArgs).getMessage();
        String printed = window.admit(fingerprint(family, detail), detail, ledgerNote);
        if (printed != null) {
            sink.warn(printed);
        }
    }

    /**
     * Fingerprint = family prefix + normalized detail: digits (ports, ids,
     * attempt counters, timestamps) are masked so one logical repeat family
     * collapses as one, while distinct providers/codes stay distinct.
     */
    private static String fingerprint(String family, String detail) {
        return family + "|" + LogCollapseWindow.normalize(detail);
    }
}
