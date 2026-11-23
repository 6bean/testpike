package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.ChatExportView;

/**
 * Session export service : turns one
 * owned conversation into a Markdown document the user can keep or feed back
 * into the knowledge base. The export is read-only by contract — it writes
 * nothing anywhere (landing content into the knowledge base is a separate,
 * confirmed path).
 */
public interface ChatSessionExportService {

    /**
     * Render one session as Markdown, ownership-checked: a stranger gets the
     * named refusal, never another user's conversation. Only sealed turns are
     * exported (the still-streaming turn belongs to the live pump); system
     * plumbing rows stay out; streamed answers are rebuilt from the ledger so
     * the file matches what the user watched.
     */
    ChatExportView renderMarkdown(long userId, long sessionId);
}
