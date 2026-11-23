package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Result of a chat user-image upload .
 * <p>The storage key is the canonical chat-image shape minted by
 * {@code StorageKeys.build} under the issuing project's segment — the same
 * value the run door re-validates through {@code ChatImageAttachments}.
 * {@code fileName} is the cleaned display label (basename only); the bytes
 * themselves never cross this boundary.
 */
public record ChatImageInfo(String objectKey, String fileName,
                            String contentType, long sizeBytes) {
}
