package com.sixbean.testpike.admin.llmconfig.api.dto;

import java.util.List;

/**
 * Admin mirror of one page of route-axis golden replay verdicts
 * . Names match the core GoldenDriftPageView
 * contract exactly: the whole filtered {@code total} travels with the window
 * and {@code pageSize} carries the clamped value actually applied.
 *
 * @param rows newest-first verdict rows inside this window
 * @param total whole filtered row count across all pages
 * @param page one-based window index in force
 * @param pageSize applied window size after the bounded clamp
 */
public record GoldenDriftPageView(List<GoldenDriftVerdict> rows, long total, int page, int pageSize) {
}
