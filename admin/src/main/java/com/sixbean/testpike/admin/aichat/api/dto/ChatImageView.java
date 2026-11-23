package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Admin mirror of core's chat-image upload result (AO batch P4 segment
 * 2c). The browser keeps the object key and hands it back inside the
 * question's attachment channel — core re-validates it at the run door.
 */
public record ChatImageView(String objectKey, String fileName,
                            String contentType, long sizeBytes) {
}
