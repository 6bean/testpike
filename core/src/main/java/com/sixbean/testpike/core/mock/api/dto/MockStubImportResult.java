package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Outcome of a contract-direct import : which drafts became
 * rule rows and which were skipped because the space already carries the same
 * method+path rule (re-import of an unchanged contract is idempotent, the
 * same posture as the spec preview apply-chain).
 *
 * @param createdStubIds ids of the rules filed by this call, input order
 * @param skippedEndpointKeys contract keys already covered — untouched, not errors
 */
public record MockStubImportResult(
        List<Long> createdStubIds,
        List<String> skippedEndpointKeys) {
}
