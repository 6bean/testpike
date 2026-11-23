package com.sixbean.testpike.admin.aichat.api.dto;

import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;

import java.util.List;

/**
 * User-facing request of the dialog stream endpoint .
 * <p>Exactly one of the two shapes is used per call: {@code prompt} non-blank
 * opens a fresh run; {@code resumeCursor} non-null re-attaches to a run already
 * in flight and replays only frames with a seq greater than the cursor (the
 * Last-Event-ID semantics carried in the body, per the single-endpoint
 * adjudication — a client that sends both is treated as a resume, the server
 * never starts a second run behind the user's back).
 * <p>{@code refObjects} carries the @-mention chips
 * of a fresh question; a resume never replays references — the run it
 * re-attaches to already holds them. Validation is core's single point
 * ({@code ChatObjectRefs}); admin mirrors the wire shape only.
 * <p>{@code attachments} carries the user-image
 * uploads of a fresh question the same way — mirror only, core's
 * {@code ChatImageAttachments} is the one validation point; a resume never
 * carries them, and old clients keep sending the shape without this field.
 */
public record ChatStreamRequest(String prompt, Long resumeCursor,
                                List<ChatObjectRef> refObjects,
                                List<ChatImageAttachment> attachments,
                                String questionContext) {
}
