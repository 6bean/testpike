package com.sixbean.testpike.core.plans.api.dto;

/**
 *
 * @param sortBy sort key (null/blank = historical id-DESC default; off-whitelist key = fail-closed 400 via SortColumnPolicy.require)
 * @param sortDir sort direction (asc = ascending, anything else = descending)
 */
public record PlanQuery(long projectId, Integer status, String keyword, int page, int size,
                        String sortBy, String sortDir) {

    /** Back-compatible form: no explicit sort keeps the historical default order. */
    public PlanQuery(long projectId, Integer status, String keyword, int page, int size) {
        this(projectId, status, keyword, page, size, null, null);
    }
}
