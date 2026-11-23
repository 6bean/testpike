package com.sixbean.testpike.admin.comment.api.dto;


public record CommentAttachmentView(Long id, Long commentId, String fileName, String objectKey,
                                    String contentType, long sizeBytes, String createdAt) {
}
