package com.sixbean.testpike.core.aichat.api.dto;

/**
 * Result of applying a confirmed dialog draft through the generic door
 * .
 * <p>The receipt names WHAT was written and WHERE, never a bare success flag:
 * the kind discriminator plus the target entity and the version the landing
 * strategy produced. Kind-specific receivers (the repair card's case/version
 * view) map from these generic fields at their own boundary.
 *
 * @param draftId the confirmable snapshot row that was applied
 * @param objectType landing strategy kind (DraftObjectType.wireName)
 * @param targetId entity the proposal wrote (NULL if the kind has no target)
 * @param appliedVersion version produced by the applied proposal (NULL if versionless)
 */
public record DraftApplyResult(long draftId, String objectType, Long targetId, Integer appliedVersion) {
}
