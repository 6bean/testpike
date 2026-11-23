package com.sixbean.testpike.core.defects.api.dto;

/**
 *
 * @param sortBy sort key (null/blank = historical id-DESC default; off-whitelist key = fail-closed 400 via SortColumnPolicy.require)
 * @param sortDir sort direction (asc = ascending, anything else = descending)
 * @param programId Project-set lens id; 0 = no lens. A positive value narrows the list to
 * the lens projects the caller can see (ProgramLensScope single source),
 * intersected with the explicit projectId when one is also given.
 */
public record DefectQuery(Long projectId, Integer status, Integer severity, Long assigneeId, Long caseId,
                          String keyword, int page, int size, String sortBy, String sortDir, long programId) {

    /** Back-compatible form: no explicit sort keeps the historical default order. */
    public DefectQuery(Long projectId, Integer status, Integer severity, Long assigneeId, Long caseId,
                       String keyword, int page, int size) {
        this(projectId, status, severity, assigneeId, caseId, keyword, page, size, null, null, 0L);
    }

    /** Back-compatible 10-parameter form (programId=0 = no lens; legacy callers keep compiling). */
    public DefectQuery(Long projectId, Integer status, Integer severity, Long assigneeId, Long caseId,
                       String keyword, int page, int size, String sortBy, String sortDir) {
        this(projectId, status, severity, assigneeId, caseId, keyword, page, size, sortBy, sortDir, 0L);
    }
}
