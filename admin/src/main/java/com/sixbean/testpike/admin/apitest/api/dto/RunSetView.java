package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Admin mirror of core ApiRunSetInfo (collection-run ledger row).
 *
 * @param status 0=queued 1=running 2=done-passed 3=done-with-failures
 *               4=interrupted by the chosen mode
 */
public record RunSetView(long id, long collectionId, long projectId, Long envId,
                         String name, String interruptionMode, int status,
                         String pinnedJson, String initialVarsJson,
                         int totalSteps, int passedCount, int failedCount, int skippedCount) {
}
