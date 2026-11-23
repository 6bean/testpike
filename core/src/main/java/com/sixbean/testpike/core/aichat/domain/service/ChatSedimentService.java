package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatSedimentView;

/**
 * "Save conversation as knowledge" landing service (AO batch phase 2b,
 * E5 sedimentation): turns one owned dialog session into a real document via
 * the shared upload pipeline and queues it for knowledge-base ingest, so the
 * conversation becomes searchable reference material instead of a dead
 * transcript. The rendered content is the phase-2a export single source —
 * already masked, system plumbing filtered, unsealed turns excluded.
 * <p>Human-triggered by contract: nothing calls this automatically; the
 * confirm-before-landing gate is the front-end button (phase 4).
 */
public interface ChatSedimentService {

    /**
     * Land one owned session's Markdown export as a knowledge document.
     *
     * @param userId    the acting owner (rejected 403 for a foreign session)
     * @param sessionId dialog session to sediment
     * @return the document the dialog became (id, display name, chunk count)
     * @throws com.sixbean.testpike.common.exception.ServiceException 400 when
     *         the dialog has no Q&amp;A content yet (a header-only shell
     *         document is never created); 409 naming the existing document
     *         when the same-minute re-save would duplicate its content
     */
    ChatSedimentView landAsKnowledge(long userId, long sessionId);
}
