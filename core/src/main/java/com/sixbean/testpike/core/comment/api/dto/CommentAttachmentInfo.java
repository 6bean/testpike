package com.sixbean.testpike.core.comment.api.dto;


public record CommentAttachmentInfo(Long id, Long commentId, String fileName, String objectKey,
                                    String contentType, long sizeBytes, String createdAt) {
}
