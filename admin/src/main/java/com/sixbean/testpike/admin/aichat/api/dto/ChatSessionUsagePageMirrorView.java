package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Admin-side mirror of the core session-attribution page (E9, phase 3):
 * the whole filtered {@code total} travels with the window, same page-door
 * shape as {@link ChatSessionPageMirrorView}.
 */
public record ChatSessionUsagePageMirrorView(List<ChatSessionUsageItemMirrorView> list, long total,
                                             int page, int pageSize) {
}
