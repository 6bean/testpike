package com.sixbean.testpike.core.script.api.dto;

/**
 * Input of an off-platform script write-back.
 * <p>Semantics: land the action JSON as a new script version through the single
 * script save path, pull the owning case back to pending review, and grade the
 * case by whether an evidence package was attached.
 *
 * @param caseId          target case (soft-deleted or missing case = 404 via the case read path)
 * @param schemaVersion   action JSON schema version (v1/v2, 04 contract)
 * @param actionJson      agent-authored action JSON
 * @param evidence        evidence package; null = bare self-report (grade 1)
 * @param expectedVersion optimistic-lock expected script version; null = skip comparison
 *                        (checked inside the single save path, stale = version conflict)
 * @param operatorId      acting user id for the audit row (nullable = no user context)
 */
public record ScriptWritebackInput(long caseId, String schemaVersion, String actionJson,
                                   ScriptWritebackEvidence evidence, Integer expectedVersion,
                                   Long operatorId) {
}
