package com.sixbean.testpike.admin.aichat.api.dto;

import java.util.List;

/**
 * Mirror of the core group-confirmation receipt crossing the admin boundary
 * . One assistant answer's
 * checked cards, each answered line by line; the write set landed in ONE
 * transaction and colliding proposals are named, not silently dropped.
 *
 * @param messageId the answer whose cards were confirmed as a group
 * @param items per-checked-card receipt lines, ordered by draft id
 */
public record DraftGroupApplyReceiptView(long messageId, List<DraftGroupApplyItemView> items) {
}
