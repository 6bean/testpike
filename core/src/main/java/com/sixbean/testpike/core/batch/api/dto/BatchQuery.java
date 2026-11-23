package com.sixbean.testpike.core.batch.api.dto;

/**
 * Batch list query (M2-α I1; 02 GET /batches, H1 project isolation).
 * <p>The default view excludes temporary batches (type=2; fast execution channels are not included in the main list).
 *
 * @param projectId Project
 * @param status Status filter (null=all)
 * @param keyword Fuzzy name search (null=no filter)
 * @param page Page number (starting from 1)
 * @param size Page size (maximum 200)
 * @param sortBy    sort key (null/blank = historical id-DESC default; off-whitelist key = fail-closed 400 via SortColumnPolicy.require)
 * @param sortDir   sort direction (asc = ascending, anything else = descending)
 * @param programId Program lens (project set) id; 0 = no lens. A positive value narrows
 *                  the list to the lens projects the caller can see (ProgramLensScope),
 *                  intersected with the explicit projectId when one is also given.
 */
public record BatchQuery(long projectId, Integer status, String keyword, int page, int size,
                         String sortBy, String sortDir, long programId) {

    /** Back-compatible form: no explicit sort keeps the historical default order. */
    public BatchQuery(long projectId, Integer status, String keyword, int page, int size) {
        this(projectId, status, keyword, page, size, null, null, 0L);
    }

    /** Back-compatible 7-parameter form (programId=0 = no lens; legacy callers keep compiling). */
    public BatchQuery(long projectId, Integer status, String keyword, int page, int size,
                      String sortBy, String sortDir) {
        this(projectId, status, keyword, page, size, sortBy, sortDir, 0L);
    }
}
