package com.sixbean.testpike.core.mock.api.dto;

import java.util.List;

/**
 * Outcome of one cassette playback : which recorded
 * frames became rule rows, which endpoint keys were skipped because the space
 * already carries that method+path (idempotence inherited from the import
 * channel), and the box's accumulated promoted_count after this run.
 *
 * @param createdStubIds rule ids filed by this call, draft order
 * @param skippedEndpointKeys cassette keys already covered — untouched, not errors
 * @param promotedCount box counter after this run (sum across playbacks)
 */
public record MockPlaybackResult(
        List<Long> createdStubIds,
        List<String> skippedEndpointKeys,
        int promotedCount) {
}
