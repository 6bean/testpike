package com.sixbean.testpike.core.comment.api.dto;


public record CommentInfo(Long id, String hostType, Long hostId, int source, Long authorId,
                          String authorName, String content, String refKey, String createdAt) {
}
