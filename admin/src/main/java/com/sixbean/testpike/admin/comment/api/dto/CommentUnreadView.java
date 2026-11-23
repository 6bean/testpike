package com.sixbean.testpike.admin.comment.api.dto;


public record CommentUnreadView(String hostType, Long hostId, int unreadCount) {
}
