package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Persist one frame of a streaming turn. {@code frameType} arrives as the wire
 * name string (the literal written into the SSE {@code event:} field) so the
 * HTTP contract never depends on Java enum ordinal/ordering; an unknown name is
 * refused with a reason that lists the legal values.
 */
public record AppendFrameRequest(long messageId, String frameType, String payload, String metaJson) {
}
