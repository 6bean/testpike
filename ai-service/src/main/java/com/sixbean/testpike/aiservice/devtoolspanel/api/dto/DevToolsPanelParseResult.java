package com.sixbean.testpike.aiservice.devtoolspanel.api.dto;

import java.util.List;

/**
 * Parser outcome for one screenshot: rows that are safe to offer on the
 * draft-card checkbox surface ({@code candidates}) and rows whose name or
 * value shows a column-width truncation signature ({@code truncatedSuspects}).
 * Suspect rows never appear in {@code candidates} - the fail-closed split is
 * structural so no downstream consumer can forget a flag check .
 */
public record DevToolsPanelParseResult(List<LoginStateCandidate> candidates,
                                       List<LoginStateCandidate> truncatedSuspects) {

    public DevToolsPanelParseResult {
        candidates = List.copyOf(candidates);
        truncatedSuspects = List.copyOf(truncatedSuspects);
    }
}
