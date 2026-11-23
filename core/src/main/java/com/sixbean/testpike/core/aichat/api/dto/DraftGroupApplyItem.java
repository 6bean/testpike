package com.sixbean.testpike.core.aichat.api.dto;

/**
 * One line of the group-confirmation receipt (AM batch piece 2,
 * acceptance IT-ITR-05): every checked card answers with WHAT it touched and
 * the OUTCOME it got — never a bare success flag.
 *
 * @param draftId the confirmable snapshot row this line describes
 * @param objectType landing strategy kind (DraftObjectType.wireName)
 * @param objectName on-screen name of the target entity (null when the
 * kind has no named target or the row is gone)
 * @param targetId entity the proposal wrote (create kinds: pinned by
 * the strategy during landing)
 * @param appliedVersion version produced by the write (NULL for versionless
 * kinds and for excluded cards)
 * @param outcome "landed" (written), "stale-excluded" (anchor collided —
 * the card turned into the one-click regenerate state,
 * nothing was written) or "not-ticked" (the human left
 * this card out of the group: still pending, kept for a
 * later confirmation, nothing was written)
 * @param detail refusal sentence for excluded cards (names the target
 * object, both versions and the exit) and for kept
 * cards (names the object and says it stays pending),
 * null when landed
 */
public record DraftGroupApplyItem(long draftId, String objectType, String objectName,
                                  Long targetId, Integer appliedVersion,
                                  String outcome, String detail) {

    public static final String OUTCOME_LANDED = "landed";
    public static final String OUTCOME_STALE_EXCLUDED = "stale-excluded";
    public static final String OUTCOME_NOT_TICKED = "not-ticked";
}
