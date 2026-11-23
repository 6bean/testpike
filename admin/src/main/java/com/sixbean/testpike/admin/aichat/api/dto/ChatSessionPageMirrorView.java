package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Admin-side mirror of the core session page view : the whole
 * filtered {@code total} travels with the window so the panel paginates over
 * the ledger, not over the page it happens to show.
 */
public record ChatSessionPageMirrorView(List<ChatSessionListItemView> list, long total,
                                        int page, int pageSize) {
}
