package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Request body opening a GROUP confirm ticket by scope wire (AN batch
 * piece D4; POST /internal/ai-chat/drafts/group/confirm-token). The scope
 * form generalizes the message-id path shape: {@code message:{id}} keeps
 * the dialog posture, {@code system:{projectId}:{objectType}} opens
 * the linkage bus's system-origin groups. Shape parsing and refusal live
 * single-sourced in the door itself, never here.
 *
 * @param groupScope canonical DraftGroupScope wire of the group being opened
 */
public record GroupScopeTicketRequest(String groupScope) {
}
