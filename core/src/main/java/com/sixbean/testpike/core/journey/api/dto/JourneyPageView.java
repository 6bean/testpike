package com.sixbean.testpike.core.journey.api.dto;

import java.util.List;

/**
 * One page of journeys under a program (S-group worst-scale contract): the whole
 * filtered {@code total} travels with the window so the wall page never counts only
 * the rows on the page it happens to show. Rows carry the header only — segments
 * stay behind the aggregate read so a 200-journey wall costs one bounded query.
 */
public record JourneyPageView(List<JourneyInfo> list, long total, int page, int pageSize) {
}
