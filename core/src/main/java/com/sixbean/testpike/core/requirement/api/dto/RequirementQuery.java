package com.sixbean.testpike.core.requirement.api.dto;

import java.util.List;

/**
 *
 * @param sortBy sort key (null/blank = historical id-DESC default; off-whitelist key = fail-closed 400 via SortColumnPolicy.require)
 * @param sortDir sort direction (asc = ascending, anything else = descending)
 * @param ids id allow-list for structured deep-link handoff (: the generation page
 * rehydrates just-created requirement points by id; null/empty = no filter)
 * @param fingerprint exact content-fingerprint lookup ( segment B; compared against the
 * stored XXXX-XXXX display form after normalization; null/blank = no filter)
 */
public record RequirementQuery(Long projectId, Integer status, String keyword, int page, int size,
                               String sortBy, String sortDir, List<Long> ids, String fingerprint) {

    /** Back-compatible form: no explicit sort keeps the historical default order. */
    public RequirementQuery(Long projectId, Integer status, String keyword, int page, int size) {
        this(projectId, status, keyword, page, size, null, null, null, null);
    }

    /** Back-compatible form without the id allow-list (pre- call sites). */
    public RequirementQuery(Long projectId, Integer status, String keyword, int page, int size,
                            String sortBy, String sortDir) {
        this(projectId, status, keyword, page, size, sortBy, sortDir, null, null);
    }

    /** Back-compatible form without the fingerprint filter (pre--slice-B call sites). */
    public RequirementQuery(Long projectId, Integer status, String keyword, int page, int size,
                            String sortBy, String sortDir, List<Long> ids) {
        this(projectId, status, keyword, page, size, sortBy, sortDir, ids, null);
    }
}
