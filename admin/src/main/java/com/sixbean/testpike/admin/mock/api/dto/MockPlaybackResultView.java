package com.sixbean.testpike.admin.mock.api.dto;

import java.util.List;

/**
 * Outcome of one cassette playback (admin bridge mirror of the core
 * MockPlaybackResult record — .
 *
 * @param createdStubIds rule ids filed by this call, draft order
 * @param skippedEndpointKeys cassette keys already covered — untouched, not errors
 * @param promotedCount box counter after this run (sum across playbacks)
 */
public record MockPlaybackResultView(
        List<Long> createdStubIds,
        List<String> skippedEndpointKeys,
        int promotedCount) {
}
