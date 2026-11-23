package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * Mirror of the core generic draft-apply receipt crossing the admin boundary
 * . The dialog lane's wire
 * carries the kind discriminator plus generic anchors; the repair card's
 * view (caseId/scriptVersion) is projected from it at the client boundary.
 *
 * @param draftId confirmable snapshot row that was applied
 * @param objectType landing strategy kind (SCRIPT_REPAIR today)
 * @param targetId entity the proposal wrote (repair: the case)
 * @param appliedVersion version produced by the write (repair: script version)
 */
public record DraftApplyReceiptView(long draftId, String objectType, Long targetId,
                                    Integer appliedVersion) {
}
