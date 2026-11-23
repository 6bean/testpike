package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Admin-side mirror of the core landing proof (core {@code InjectLanding}):
 * the injection joined this streaming message under this session-wide seq.
 * Kept as its own type because the admin module never imports core classes;
 * {@code null} is meaningful — it is core's honest "no turn is running" answer,
 * which the bridge turns into a regular message submission.
 *
 * @param messageId the streaming assistant message the injection joined
 * @param seq       the allocated ledger seq of the landed user-inject frame
 */
public record ChatInjectLanding(long messageId, long seq) {
}
