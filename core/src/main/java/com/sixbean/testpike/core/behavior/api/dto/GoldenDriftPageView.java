package com.sixbean.testpike.core.behavior.api.dto;

import java.util.List;

/**
 * One page of route-axis golden replay verdicts .
 * The whole filtered {@code total} travels with the window so the ledger page
 * never counts only the rows it happens to show, and {@code pageSize} carries
 * the value actually applied — an oversized request is clamped to the bounded
 * window and the caller sees the clamp instead of a silent truncation. Shape
 * mirrors the journey listing contract (S-group worst-scale).
 *
 * @param rows newest-first verdict rows inside this window
 * @param total whole filtered row count across all pages
 * @param page one-based window index in force
 * @param pageSize applied window size after the bounded clamp
 */
public record GoldenDriftPageView(List<GoldenDriftVerdict> rows, long total, int page, int pageSize) {
}
