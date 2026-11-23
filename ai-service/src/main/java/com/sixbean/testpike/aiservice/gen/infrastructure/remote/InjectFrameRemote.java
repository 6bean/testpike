package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

/**
 * Mirror of one pending user-inject frame read from the core steering door
 * .
 * Only the fields the ai-service consumer needs are mirrored: {@code seq}
 * drives the turn cursor, {@code payload} is the injected text the model must
 * see. Absent JSON fields land as nulls like every other tail mirror here.
 *
 * @param messageId streaming assistant message the injection joined
 * @param seq session-wide ledger seq of the user_inject frame
 * @param payload the injected text verbatim
 */
public record InjectFrameRemote(long messageId, long seq, String payload) {
}
