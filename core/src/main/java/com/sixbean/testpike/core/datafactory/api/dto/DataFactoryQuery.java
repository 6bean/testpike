package com.sixbean.testpike.core.datafactory.api.dto;

/**
 * <p>sortBy must hit the core data-factory column whitelist (SortColumnPolicy
 * require, fail-closed 400 naming the allowed set); a null/blank sortBy keeps
 * the historical id DESC default order. sortDir asc means ascending, anything
 * else (incl. null) means descending.
 *
 * @param sortBy whitelist key (id/name/type/status/created_at), null = default
 * @param sortDir asc for ascending; anything else = descending
 */
public record DataFactoryQuery(long projectId, String keyword, Integer type, int page, int size,
                               String sortBy, String sortDir) {

    /** Legacy five-argument form: delegates with no sort (zero behavior drift). */
    public DataFactoryQuery(long projectId, String keyword, Integer type, int page, int size) {
        this(projectId, keyword, type, page, size, null, null);
    }
}
