package com.sixbean.testpike.common.aichat;

/**
 * One @-mentioned platform object carried through the dialog chain (AO batch
 * knife 4b): the chip the user dropped into the composer, normalised into
 * the triple (kind, id, title) the grounding side consumes.
 *
 * @param kind object family, one of {@link ChatObjectRefs#KINDS}
 * @param id row id inside the session's project segment
 * @param title human-readable label shown to the model and the user
 */
public record ChatObjectRef(String kind, long id, String title) {
}
