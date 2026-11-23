package com.sixbean.testpike.core.comment.api.dto;

import java.util.List;

/**
 * Comment creation input parameters (migration artifact; the core internal endpoint receives them, and userId is resolved from the admin login state and passed through).
 *
 * @param hostType host type review/case/batch/defect (whitelist validation, returning a human-readable 400 response for invalid values).
 * @param hostId host primary key (the review host uses case_id, finalized as A).
 * @param content body (non-empty and at most 2000 characters, human-readable without truncation per redline 10).
 * @param mentions list of mentioned user IDs (nullable; registered tp_user users receive a MENTION in-site message, and ghost users are removed).
 */
public record CommentCreateInput(String hostType, Long hostId, String content, List<Long> mentions) {
}
