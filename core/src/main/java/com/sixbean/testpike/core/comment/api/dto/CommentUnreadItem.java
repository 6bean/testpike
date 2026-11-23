package com.sixbean.testpike.core.comment.api.dto;

/**
 * Host unread badge item (migration artifact; aggregate using a single SQL comparison, prohibit N+1 queries, and omit hosts with no unread items or comments).
 *
 * @param hostType host type
 * @param hostId host primary key
 * @param unreadCount number of unread comments (count where id > last_read_comment_id)
 */
public record CommentUnreadItem(String hostType, Long hostId, int unreadCount) {
}
