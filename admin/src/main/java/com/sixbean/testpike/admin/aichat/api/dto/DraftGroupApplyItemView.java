package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of one receipt line of the core group-confirmation door (AM batch
 * piece 2b; core DraftGroupApplyItem). Every checked card answers with
 * WHAT it touched and the OUTCOME it got — never a bare success flag.
 *
 * @param draftId confirmable snapshot row this line describes
 * @param objectType landing strategy kind (DraftObjectType.wireName)
 * @param objectName on-screen name of the target entity (null when the
 * kind has no named target or the row is gone)
 * @param targetId entity the proposal wrote (create kinds: pinned by
 * the strategy during landing)
 * @param appliedVersion version produced by the write (NULL for versionless
 * kinds and for excluded cards)
 * @param outcome "landed" (written), "stale-excluded" (anchor collided —
 * the card turned into the one-click regenerate state)
 * or "not-ticked" (left out of this confirmation: the
 * card stays pending for a later one)
 * @param detail refusal sentence for excluded cards (names the target
 * object, both versions and the exit) and for kept cards
 * (names the object, says nothing was written and that
 * it remains pending), null when landed
 */
public record DraftGroupApplyItemView(long draftId, String objectType, String objectName,
                                      Long targetId, Integer appliedVersion,
                                      String outcome, String detail) {
}
