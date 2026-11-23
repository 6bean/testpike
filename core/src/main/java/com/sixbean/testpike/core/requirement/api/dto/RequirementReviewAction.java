package com.sixbean.testpike.core.requirement.api.dto;

/**
 * Review actions granted on a requirement by an explicit submit-review.
 * The ledger vocabulary mirrors the case review posture (pass / need-change /
 * reject) while staying scoped to the requirement aggregate.
 */
public enum RequirementReviewAction {

    /** The current version is approved: it flips a changed row back to
     *  active and unlocks knowledge-base ingest for that exact version. */
    PASS,

    /** The reviewer asks the author to fix something; a remark is mandatory. */
    NEED_CHANGE,

    /** The reviewer refuses the change set; a remark is mandatory. */
    REJECT;

    /** Case-insensitive parsing for transport layers (null-safe). */
    public static RequirementReviewAction from(String raw) {
        for (RequirementReviewAction action : values()) {
            if (action.name().equalsIgnoreCase(raw)) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知的需求评审动作: " + raw);
    }
}
