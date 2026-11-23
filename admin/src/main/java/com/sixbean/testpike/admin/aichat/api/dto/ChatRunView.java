package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Admin-side mirror of the core run-opening result ({@code ChatRunInfo}) —
 * the two ledger rows one run is made of. The pump consumes
 * {@code assistantMessageId}: it is the block whose frames stay deliverable
 * even after the worker seals the run before the pump's first poll.
 */
public record ChatRunView(
        long userMessageId,
        long assistantMessageId) {
}
