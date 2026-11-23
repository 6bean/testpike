package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Outcome of one contract-direct import as served by the admin bridge
 * (mirror of the core result record): which drafts became rule rows and
 * which were skipped because the space already covers the same
 * method+path — re-import of an unchanged contract stays idempotent.
 *
 * @param createdStubIds      ids of the rules filed by this call, in draft order
 * @param skippedEndpointKeys contract keys already covered — untouched, not errors
 */
public record MockStubImportResultView(
        List<Long> createdStubIds,
        List<String> skippedEndpointKeys) {
}
