package com.sixbean.testpike.core.aichat.api.dto;

import java.util.List;

/**
 * One page of session-attribution rows (E9 cost dashboard, AO batch
 * phase 3). Same shape as every other core page view: the whole filtered
 * {@code total} travels with the window so the table's footer never counts
 * only the page it happens to show. Rows are ordered by window tokens
 * descending — the conversation that burned the most this month reads first.
 */
public record ChatSessionUsagePageView(List<ChatSessionUsageItemView> list, long total,
                                       int page, int pageSize) {
}
