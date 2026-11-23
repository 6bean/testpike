package com.sixbean.testpike.core.script.domain.component;

/**
 * In-core event carrying a landed off-site script write-back .
 * <p>Published inside the write-back transaction; the platform re-validation
 * step listens AFTER_COMMIT, so a reproof task is only scheduled once the
 * write-back is durable and scheduling errors can never unwind it.
 *
 * @param caseId case the write-back landed on
 * @param projectId owning project (environment lookup scope)
 * @param schemaVersion action JSON schema version of the landed script
 * @param actionJson the landed action JSON snapshot (what the trial executes)
 * @param operatorId write-back operator for the reproof audit trail, nullable
 */
public record ScriptWritebackLanded(long caseId, long projectId, String schemaVersion,
                                    String actionJson, Long operatorId) {
}
