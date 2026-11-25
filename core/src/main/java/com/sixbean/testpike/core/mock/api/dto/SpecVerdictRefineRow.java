package com.sixbean.testpike.core.mock.api.dto;

/**
 * One AI-refined advice row of a backfill round: the advice fields the model
 * owns. The conflict-stub identity stays whatever the deterministic floor
 * recorded — intake refines wording and grades, it never re-detects conflicts.
 */
public record SpecVerdictRefineRow(
        String endpointKey,
        String verdict,
        String reason,
        String conflictAction,
        String impactJson,
        int realism,
        int confidence) {
}
