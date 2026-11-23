package com.sixbean.testpike.core.apitest.api.dto;

/**
 * Creation input for a whole-collection run .
 *
 * @param collectionId target collection (404 if missing)
 * @param envId environment profile; when present it must belong to the
 * collection's project — same host-whitelist precondition as
 * single-request runs (SSRF red line)
 * @param name optional label for the run-set ledger row
 * @param interruptionMode one of STOP_ON_ASSERT_FAIL / CONTINUE_AND_SUMMARY /
 * CHAIN_DEP_ONLY (form enums, validated against the
 * shared plan grammar)
 */
public record RunSetCreateInput(Long collectionId, Long envId, String name, String interruptionMode) {
}
