package com.sixbean.testpike.aiservice.devtoolspanel.api.dto;

import java.util.List;

/**
 * Outcome of staging one parsed DevTools panel screenshot into the transient
 * core slot . The token is the only handle the
 * dialog keeps; {@code metadataLines} describe each parked row by kind, name
 * and origin (plus the red-mark flag) WITHOUT the value — this whole object
 * is allowed to ride the observation block and the logs, a credential string
 * is not (piece E red line: values transit memory and Redis only).
 *
 * @param token staging token returned by the core door, null when nothing
 * was staged
 * @param metadataLines value-free row descriptions
 * @param candidateCount rows parked in the slot
 * @param truncatedSuspectCount rows the parser split off as column-width
 * truncated (fail-closed, never parked, D4)
 */
public record PanelStageOutcome(String token, List<String> metadataLines,
                                int candidateCount, int truncatedSuspectCount) {

    /** Nothing staged — the enhancement leg stays silent. */
    public static final PanelStageOutcome NONE =
            new PanelStageOutcome(null, List.of(), 0, 0);

    public PanelStageOutcome {
        metadataLines = List.copyOf(metadataLines);
    }

    /** True when a live slot token backs the metadata lines. */
    public boolean staged() {
        return token != null && !metadataLines.isEmpty();
    }
}
