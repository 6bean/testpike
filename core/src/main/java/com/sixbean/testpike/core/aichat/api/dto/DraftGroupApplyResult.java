package com.sixbean.testpike.core.aichat.api.dto;

import java.util.List;

/**
 * Receipt of one group confirmation : one assistant
 * answer's cards, EVERY one answered line by line — landed, pulled out of the
 * write set by an anchor collision, or left unticked and still pending. The
 * write set landed in ONE transaction; colliding proposals are named, not
 * silently dropped, and the human sees exactly which card went where without
 * opening the DB.
 *
 * @param groupScope the resolved group wire (the dialog shape stays
 * {@code message:{id}}; piece B adds
 * {@code system:{projectId}:{objectType}})
 * @param messageId the answer whose cards were confirmed — dialog scope
 * only; system-scoped receipts carry 0, never a fake id
 * @param items per-card receipt lines of that group, ordered by draft id
 */
public record DraftGroupApplyResult(String groupScope, long messageId,
                                    List<DraftGroupApplyItem> items) {
}
