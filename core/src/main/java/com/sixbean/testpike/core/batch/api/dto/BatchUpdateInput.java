package com.sixbean.testpike.core.batch.api.dto;

import java.util.List;

/**
 * Batch draft edit input: PUT /batches/{id} can be edited only in draft state.
 * <p> Null fields mean no update, and adding or removing cases is idempotent, so repeated additions do not insert duplicates and removing nonexistent members is silently ignored.
 * <p> version is optional, where non-null triggers optimistic locking validation and a stale version returns 40901 with a human-readable message, while null maintains compatibility with legacy callers.
 * Fetch the current version and bump it without changing existing behavior.
 *
 * @param name new name (null means no change)
 * @param envId new environment (null means no change)
 * @param addCaseIds members to add (null or empty means no addition)
 * @param removeCaseIds members to remove (null or empty means no deletion)
 * @param version expected version for optimistic locking (null means validation is not enforced)
 * @param poolTagId resource pool tag id (null means no change; if non-null, it must have type=7 and belong to the same project, otherwise it returns 400 with a human-readable message)
 * @param browser unified browser for batch execution (null means no change; if non-null, it must match the kernel whitelist and belong to the environment matrix)
 * @param cleanAppData automatic app-data cleanup flag for batch execution (0/1; null means no change, following the same "null means no update" convention as other edit fields)
 */
public record BatchUpdateInput(String name, Long envId, List<Long> addCaseIds,
                               List<Long> removeCaseIds, Integer version, Long poolTagId,
                               String browser, Integer cleanAppData) {

    /** Legacy 7-argument compatibility constructor (no cleanup flag means no change, and existing callers require no modification) */
    public BatchUpdateInput(String name, Long envId, List<Long> addCaseIds,
                            List<Long> removeCaseIds, Integer version, Long poolTagId,
                            String browser) {
        this(name, envId, addCaseIds, removeCaseIds, version, poolTagId, browser, null);
    }

    /** Legacy 6-argument compatibility constructor (no browser means no change, and existing callers require no modification) */
    public BatchUpdateInput(String name, Long envId, List<Long> addCaseIds,
                            List<Long> removeCaseIds, Integer version, Long poolTagId) {
        this(name, envId, addCaseIds, removeCaseIds, version, poolTagId, null);
    }

    /** Legacy 5-argument compatibility constructor (no resource pool means the pool is not changed, and existing callers require no modification) */
    public BatchUpdateInput(String name, Long envId, List<Long> addCaseIds,
                            List<Long> removeCaseIds, Integer version) {
        this(name, envId, addCaseIds, removeCaseIds, version, null, null);
    }

    /** Legacy 4-argument compatibility constructor (no version means optimistic locking is not validated, and existing callers require no modification) */
    public BatchUpdateInput(String name, Long envId, List<Long> addCaseIds,
                            List<Long> removeCaseIds) {
        this(name, envId, addCaseIds, removeCaseIds, null, null, null);
    }
}
