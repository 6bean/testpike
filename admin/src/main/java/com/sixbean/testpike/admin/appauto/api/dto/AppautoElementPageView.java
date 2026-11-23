package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;

/**
 * Paginated element library listing mirrored from the core page contract
 * (page/pageSize surfaces for management pages; total is the row count of
 * the same predicate, computed core-side).
 */
public record AppautoElementPageView(List<AppautoElementView> list, long total) {
}
