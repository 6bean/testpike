package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Open one dialog turn through the internal ledger door (ai-service gateway call).
 * {@code role} is validated by the ledger service itself — the controller stays
 * thin and never re-implements the state machine.
 */
public record OpenMessageRequest(String role, String content) {
}
