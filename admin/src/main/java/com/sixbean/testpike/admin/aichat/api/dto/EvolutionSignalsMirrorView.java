package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Admin-side mirror of the core observation-signal rollup (self-evolution
 * loop, AF-i phase 2b). Field names match the core view one-to-one — the
 * bridge crosses HTTP by name, so a rename on either side silently drops a
 * column on the page (the bridge contract test pins this).
 *
 * @param month the yyyy-MM window the numbers cover
 * @param projectId project segment the dialog signals cover (null = all projects)
 * @param sessionsWithSealedAnswer sessions with at least one sealed assistant answer in the window
 * @param sessionsWithFollowUp of those, the ones the user returned to afterwards
 * @param followUpRate sessionsWithFollowUp / sessionsWithSealedAnswer, 0 when empty
 * @param thumbsUpCount 👍 left on sealed answers inside the window
 * @param thumbsDownCount 👎 left on sealed answers inside the window
 * @param errorTurns assistant turns sealed as error inside the window
 * @param toolFailures per-tool failure groups, heaviest first (platform-wide ledger)
 */
public record EvolutionSignalsMirrorView(String month,
                                         Long projectId,
                                         long sessionsWithSealedAnswer,
                                         long sessionsWithFollowUp,
                                         double followUpRate,
                                         long thumbsUpCount,
                                         long thumbsDownCount,
                                         long errorTurns,
                                         List<ToolFailureMirrorRow> toolFailures) {

    /**
     * One tool's failure group inside the window.
     *
     * @param toolName     tool name as recorded on the invocation trail
     * @param failureCount how many calls failed
     * @param lastError    newest error text of the group (may be null)
     */
    public record ToolFailureMirrorRow(String toolName, long failureCount, String lastError) {
    }
}
