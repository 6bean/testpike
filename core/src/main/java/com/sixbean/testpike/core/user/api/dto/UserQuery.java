package com.sixbean.testpike.core.user.api.dto;

/**
 *
 * @param sortBy sort key (null/blank = historical id-ASC default; off-whitelist key = fail-closed 400 via SortColumnPolicy.require)
 * @param sortDir sort direction (asc = ascending, anything else = descending)
 */
public record UserQuery(String keyword, Integer role, Integer status, int page, int size,
                        String sortBy, String sortDir) {

    /** Back-compatible form: no explicit sort keeps the historical default order. */
    public UserQuery(String keyword, Integer role, Integer status, int page, int size) {
        this(keyword, role, status, page, size, null, null);
    }
}
