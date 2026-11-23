package com.sixbean.testpike.core.suites.api.dto;

/**
 *
 * @param sortBy sort key (null/blank = historical id-DESC default; off-whitelist key = fail-closed 400 via SortColumnPolicy.require)
 * @param sortDir sort direction (asc = ascending, anything else = descending)
 */
public record SuiteQuery(long projectId, String keyword, Integer status, int page, int size,
                         String sortBy, String sortDir) {

    /** Back-compatible form: no explicit sort keeps the historical default order. */
    public SuiteQuery(long projectId, String keyword, Integer status, int page, int size) {
        this(projectId, keyword, status, page, size, null, null);
    }
}
