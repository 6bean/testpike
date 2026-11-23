package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Admin mirror of core RunSetCreateInput . Field names are the
 * wire contract with /internal/apitest/run-sets — a drift silently drops values
 * during deserialization (RunSetBridgeTest locks the shape).
 *
 * @param collectionId target collection (404 if missing)
 * @param envId environment profile; cross-project 400 guarded in core
 * @param name optional ledger label
 * @param interruptionMode STOP_ON_ASSERT_FAIL / CONTINUE_AND_SUMMARY / CHAIN_DEP_ONLY
 */
public record RunSetCreateRequest(Long collectionId, Long envId, String name,
                                  String interruptionMode) {
}
