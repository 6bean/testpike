package com.sixbean.testpike.admin.ai.api.dto;

/**
 * Mirror of core {@code ExtractionStageRow} : one
 * landed stage step of a running extraction, replayed on the user-facing
 * channel in landing order. The mirror must not drop fields.
 *
 * @param code step identity within the generation (start / group-i / bucket-j / reconcile-r)
 * @param label user-facing step text, e.g. "grouping 2/5"
 * @param at last landing time of this step (YYYY-MM-DDTHH:mm:ss)
 */
public record ExtractionStageView(String code, String label, String at) {
}
