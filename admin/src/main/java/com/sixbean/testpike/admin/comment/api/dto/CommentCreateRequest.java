package com.sixbean.testpike.admin.comment.api.dto;

import java.util.List;


public record CommentCreateRequest(String hostType, Long hostId, String content, List<Long> mentions) {
}
