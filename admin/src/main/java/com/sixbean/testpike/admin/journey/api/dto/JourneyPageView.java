package com.sixbean.testpike.admin.journey.api.dto;

import java.util.List;

/**
 * Admin mirror of the core journey page contract (S-group worst-scale wall):
 * component names match {@code core.journey.api.dto.JourneyPageView} exactly
 * because the bridge matches by name; {@code total} is the whole filtered
 * count so the wall page never counts only the rows it happens to show.
 */
public record JourneyPageView(List<JourneyView> list, long total, int page, int pageSize) {
}
