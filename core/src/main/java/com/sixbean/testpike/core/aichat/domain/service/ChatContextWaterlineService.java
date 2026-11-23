package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatContextWaterlineView;

/**
 * Context waterline read door : the single server-side
 * source of the numbers behind the dialog panel's waterline bar — token
 * consumption against the session budget, turns folded into the rolling
 * summary, pinned facts in play. Read-only; ownership is single-sourced in
 * {@link ChatSessionService#requireOwned} (AF-g posture, no second check).
 */
public interface ChatContextWaterlineService {

    /**
     * Waterline of one owned session.
     *
     * @throws com.sixbean.testpike.common.exception.ServiceException when the
     *         session does not belong to {@code userId} — refused before any
     *         ledger or config read
     */
    ChatContextWaterlineView waterline(long sessionId, long userId);
}
