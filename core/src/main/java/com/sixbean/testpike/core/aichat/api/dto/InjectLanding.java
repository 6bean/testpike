package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Proof that a mid-turn steering injection was accepted: the frame
 * landed on the turn that is currently streaming, under the session-wide seq
 * the browser cursor already understands. A null landing (no streaming turn)
 * is carried by the caller as a degrade-to-regular-message decision, never as
 * a silently dropped text.
 *
 * @param messageId the streaming assistant message the injection joined
 * @param seq the allocated ledger seq of the landed user-inject frame
 */
public record InjectLanding(long messageId, long seq) {
}
