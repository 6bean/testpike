package com.sixbean.testpike.aiservice.aichat.infrastructure.remote;

/**
 * Step evidence mirror for the dialog replay context :
 * line names align 1:1 with core {@code executor.api.dto.ExecStepEvidenceView}
 * (ai-service has no core-module dependency, so the remote keeps its own exact
 * mirror — precedent = ExecTaskResultRemote, pitfall 51b: no ignore-unknown,
 * full-field mirror is the base path). The presence read only ever consumes
 * the objectKey columns; the presigned URLs are for the browser surface and
 * must not enter a prompt.
 */
public record ExecStepEvidenceRemote(long taskId, int stepNo, String action,
                                     String screenshotKey, String screenshotUrl,
                                     String viewTreeKey, String viewTreeUrl,
                                     String logTailKey, String logTailUrl,
                                     String recordKey, String recordUrl,
                                     String netTimelineKey, String netTimelineUrl,
                                     String perfTimelineKey, String perfTimelineUrl) {
}
