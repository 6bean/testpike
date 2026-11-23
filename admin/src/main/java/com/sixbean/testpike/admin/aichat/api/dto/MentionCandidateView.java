package com.sixbean.testpike.admin.aichat.api.dto;

/**
 * One @-mention candidate of the dialog composer .
 * <p>The shape IS the wire triple {@code ChatObjectRef} canonicalises —
 * kind/id/title — so what the palette shows and what the run pins are the
 * same contract with zero mapping layer. Titles come from the core assembly
 * rows (never the browser), keeping the label honest after a rename.
 *
 * @param kind "skill" or "tool" (the families this palette adds)
 * @param id tp_skill.id / tp_tool.id
 * @param title display label, also the digest title the chip carries
 */
public record MentionCandidateView(String kind, long id, String title) {
}
