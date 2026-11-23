package com.sixbean.testpike.core.document.api.dto;

/**
 * One landed stage row of a running extraction .
 * Written by ai-service as each step completes (run start, grouping i/n,
 * final bucket j/m, reconcile round) and replayed by the preview endpoint in
 * landing order, so the in-progress face can answer "which step, when did it
 * move last" instead of staring at a silent spinner.
 *
 * @param code step identity within the generation (start / group-i / bucket-j / reconcile-r)
 * @param label user-facing step text, e.g. "grouping 2/5"
 * @param at last landing time of this step, ISO-ish local string (YYYY-MM-DDTHH:mm:ss)
 */
public record ExtractionStageRow(String code, String label, String at) {
}
