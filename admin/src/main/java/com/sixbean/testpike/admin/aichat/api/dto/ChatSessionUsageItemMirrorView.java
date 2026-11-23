package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Admin-side mirror of the core session-attribution row (E9 cost dashboard,
 * AO batch phase 3). Field-for-field with the core contract — the bridge
 * never renames or re-derives the two token columns: {@code windowTokens} is
 * the month window recomputed from message rows, {@code sessionTotalTokens}
 * is the lifetime ledger (an honest drift pair for reconciliation).
 */
public record ChatSessionUsageItemMirrorView(long sessionId, long projectId, String projectName,
                                             String userName, String title, int source,
                                             long assistantTurns, long windowTokens,
                                             long sessionTotalTokens) {
}
