package com.sixbean.testpike.aiservice.aichat.infrastructure.remote;

/**
 * Narrow read mirror of the case script behind a repair proposal (AO batch
 * knife 3a): the dialog draft assembly needs only the current action
 * JSON (the "before" of the diff), its schema version and the script version
 * line number — the version doubles as the optimistic-lock anchor the
 * confirmation chain (knife 3b) re-checks before anything lands.
 * <p>Reuses core's existing GET /internal/scripts/{caseId}; Boot default
 * FAIL_ON_UNKNOWN_PROPERTIES=false keeps this tail-shaped mirror compatible
 * with the full ScriptInfo payload (RepairTaskView precedent, do not widen).
 *
 * @param caseId owning case (draft card anchor)
 * @param schemaVersion action-JSON envelope version (passthrough on apply)
 * @param actionJson current script body (proposal input + diff "before")
 * @param version script version-line number (baseVersion lock anchor)
 */
public record ScriptView(long caseId, String schemaVersion, String actionJson, int version) {
}
