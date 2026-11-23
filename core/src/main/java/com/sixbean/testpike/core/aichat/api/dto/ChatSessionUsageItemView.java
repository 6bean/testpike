package com.sixbean.testpike.core.aichat.api.dto;

/**
 * One session-attribution row for the cost dashboard (E9 reconciliation, AO
 * batch phase 3).
 * <p>Two token columns travel on purpose: {@code windowTokens} is recomputed
 * from the message rows inside the asked natural month (the reconciliation
 * denominator), {@code sessionTotalTokens} is the lifetime session ledger.
 * A drift between them for an all-in-window month is visible on the page
 * instead of being quietly absorbed.
 *
 * @param sessionId conversation id (drill-down key)
 * @param projectId owning project segment
 * @param projectName project name as shown in the row
 * @param userName owner display name (initiator dimension wording)
 * @param title conversation title
 * @param source entry surface: 1 dock / 2 standalone page / 3 badge deep-link
 * @param assistantTurns sealed answers inside the window that carry usage
 * @param windowTokens prompt+completion tokens summed over the window's message rows
 * @param sessionTotalTokens lifetime ledger on the session row (atomic accumulation)
 */
public record ChatSessionUsageItemView(long sessionId, long projectId, String projectName,
                                       String userName, String title, int source,
                                       long assistantTurns, long windowTokens,
                                       long sessionTotalTokens) {
}
