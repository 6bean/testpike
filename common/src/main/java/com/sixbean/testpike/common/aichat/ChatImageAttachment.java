package com.sixbean.testpike.common.aichat;

/**
 * One user-supplied image attached to a dialog question (AO batch P4
 * mobile-photo bug report): the upload door returns the storage object key
 * and the display name, and the stream request carries the pair through the
 * canonicalisation point {@link ChatImageAttachments} onto the run message.
 * <p>Deliberately NOT an object reference chip: {@code ChatObjectRefs} pins
 * the (kind, id, title) triple of platform rows — a user photo is file bytes
 * in storage, never a row, so it rides its own channel and the chip semantics
 * stay untouched.
 *
 * @param objectKey canonical storage key ({prefix}/{projectId}/chat-image/...)
 * issued by the upload door
 * @param fileName display label shown to the model and the user (cut, not
 * refused, when longer than {@link ChatImageAttachments#FILE_NAME_MAX_CHARS})
 */
public record ChatImageAttachment(String objectKey, String fileName) {
}
