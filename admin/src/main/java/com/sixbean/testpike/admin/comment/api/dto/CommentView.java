package com.sixbean.testpike.admin.comment.api.dto;


public record CommentView(Long id, String hostType, Long hostId, int source, Long authorId,
                          String authorName, String content, String refKey, String createdAt) {
}
