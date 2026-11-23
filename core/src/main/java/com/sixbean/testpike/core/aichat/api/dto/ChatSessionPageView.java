package com.sixbean.testpike.core.aichat.api.dto;

import java.util.List;

/**
 * One page of the dialog history list . Same shape as every
 * other core page view ({@code MockHitPageView} precedent): the whole filtered
 * {@code total} travels with the window so the panel's pagination never counts
 * the page it happens to show.
 */
public record ChatSessionPageView(List<ChatSessionListItemInfo> list, long total, int page, int pageSize) {
}
