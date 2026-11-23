package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

/**
 * Wire body opening a scope-shaped group ticket core-side (POST
 * /internal/ai-chat/drafts/group/confirm-token, AN batch piece D4).
 * Field-for-field twin of the core request record; the canonical wire
 * grammar is owned by core, this class only carries it.
 *
 * @param groupScope canonical DraftGroupScope wire (system:{projectId}:{type})
 */
public record GroupScopeTicketPayload(String groupScope) {
}
