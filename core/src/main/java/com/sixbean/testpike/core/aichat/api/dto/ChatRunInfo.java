package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Result of opening a dialog run: the two ledger rows the run is made of —
 * the user echo (already sealed as done) and the assistant placeholder
 * (streaming, the frame window the ai-service writer streams into).
 */
public record ChatRunInfo(long userMessageId, long assistantMessageId) {
}
