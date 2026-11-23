package com.sixbean.testpike.core.apitest.api.dto;

/**
 * Collection-run ledger view .
 *
 * @param status 0=queued 1=running 2=done-passed 3=done-with-failures
 * 4=interrupted by the chosen mode
 * @param pinnedJson request snapshots frozen at launch, tree order
 * @param initialVarsJson pool snapshot at launch (display/bookkeeping only)
 */
public record ApiRunSetInfo(long id, long collectionId, long projectId, Long envId,
                            String name, String interruptionMode, int status,
                            String pinnedJson, String initialVarsJson,
                            int totalSteps, int passedCount, int failedCount, int skippedCount) {
}
