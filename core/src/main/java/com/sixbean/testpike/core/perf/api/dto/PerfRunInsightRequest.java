package com.sixbean.testpike.core.perf.api.dto;

/**
 * Run-insight request on the core orchestration leg (B-11 : carries
 * the assembled attribution facts block down to the ai-service insight door.
 * Field names mirror the ai-service PerfInsightRequest record so the RestClient
 * bridge forwards without translation.
 *
 * @param projectId project anchor (LLM routing + call-log cost attribution)
 * @param factsBlock plain-text attribution snapshot assembled by core from its
 * single-source read faces; the ai leg renders it verbatim
 * into the prompt and never re-derives the numbers
 * @param runId the run being explained ( process-event anchor; the ai
 * leg keys its event tail on this exact id)
 */
public record PerfRunInsightRequest(
        Long projectId,
        String factsBlock,
        Long runId) {
}
