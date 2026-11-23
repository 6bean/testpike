package com.sixbean.testpike.core.apiaccess.api.dto;

/**
 * Body of the POST catalog door (same listing as the GET door, offered as a body because the
 * built-in HTTP tool form dials POST).
 *
 * @param keyword       filter over key, path and parameter names (null = all)
 * @param onlyReachable true keeps only entries the door would serve (null = true)
 */
public record ApiAccessCatalogRequest(
        String keyword,
        Boolean onlyReachable) {

    /** Effective filter: listings default to reachable-only, the safe direction. */
    public boolean reachableOnlyOrDefault() {
        return onlyReachable == null || onlyReachable;
    }
}
