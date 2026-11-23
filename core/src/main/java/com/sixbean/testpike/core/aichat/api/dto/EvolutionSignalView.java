package com.sixbean.testpike.core.aichat.api.dto;

import com.sixbean.testpike.core.tool.domain.component.ExternalCallErrorClass;

import java.util.List;

/**
 * Observation-signal rollup for the self-evolution loop (AF-i phase 2).
 * <p>Every number recomputes from a ledger that already exists — the dialog
 * message rows, the session rows, and the tool invocation trail — so the
 * loop never grows a parallel observation store. Dialog signals are scoped
 * to the asked project segment; the tool-failure ledger is platform-wide and
 * travels the same window.
 *
 * @param month the yyyy-MM window the numbers cover
 * @param projectId project segment the dialog signals cover (null = all projects)
 * @param sessionsWithSealedAnswer sessions with at least one sealed assistant answer in the window
 * @param sessionsWithFollowUp of those, the ones the user returned to (another message after the answer)
 * @param followUpRate sessionsWithFollowUp / sessionsWithSealedAnswer, 0 when the denominator is empty
 * @param thumbsUpCount 👍 left on sealed answers inside the window
 * @param thumbsDownCount 👎 left on sealed answers inside the window (each may carry one complaint line)
 * @param errorTurns assistant turns sealed as error inside the window (a failed run, not a rating)
 * @param toolFailures per-tool failure groups, heaviest first (platform-wide invocation ledger)
 * @param externalCallErrors GA5: failed external /mcp calls (caller=mcp) bucketed by error class,
 * declared classes always present (zero counts included) so the
 * distribution shape never depends on which vocabulary happened to match
 */
public record EvolutionSignalView(String month,
                                  Long projectId,
                                  long sessionsWithSealedAnswer,
                                  long sessionsWithFollowUp,
                                  double followUpRate,
                                  long thumbsUpCount,
                                  long thumbsDownCount,
                                  long errorTurns,
                                  List<ToolFailureRow> toolFailures,
                                  List<ExternalCallErrorRow> externalCallErrors) {

    /**
     * One tool's failure group inside the window.
     *
     * @param toolName     tool name as recorded on the invocation trail
     * @param failureCount how many calls failed
     * @param lastError    newest error text of the group (may be null when the trail carried none)
     */
    public record ToolFailureRow(String toolName, long failureCount, String lastError) {
    }

    /**
     * One external-call error class inside the window (GA5 read-side rollup;
     * the recorded failure text is classified at observation time, no new
     * ledger column and no writer change).
     *
     * @param errorClass which operational bucket the text matched (null text never happens — unmatched stays UNCLASSIFIED)
     * @param count      how many failed external calls land in this class
     */
    public record ExternalCallErrorRow(ExternalCallErrorClass errorClass, long count) {
    }
}
