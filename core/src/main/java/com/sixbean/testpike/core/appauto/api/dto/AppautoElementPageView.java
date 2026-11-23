package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;

/**
 * Paginated element library listing (page/pageSize contract for management
 * surfaces; total reports the unfiltered-by-page row count of the same
 * predicate).
 */
public record AppautoElementPageView(List<AppautoElementView> list, long total) {
}
