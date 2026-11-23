package com.sixbean.testpike.common.aibatch;

/**
 * One structured entry of the AI batch generation request .
 * <p>Cross-module contract payload shared by admin (user-facing DTO) and core (persistence +
 * MQ envelope). Each item carries the requirement text plus an optional requirement-point ID;
 * when the ID is present the generated case is linked back to that requirement through the
 * existing single-source link path, closing the coverage-gap loop.
 *
 * @param text natural-language requirement or manual test objective (non-blank, max length validated once in core)
 * @param requirementId optional associated requirement point id (null for plain-text entries)
 * @param sourceAnchor optional evidence anchor carried in the envelope (stored later by the lineage batch; ignored by persistence here)
 */
public record AiBatchItem(String text, Long requirementId, String sourceAnchor) {
}
