package com.sixbean.testpike.core.executor.api.dto;


public record ExecStepEvidenceView(long taskId, int stepNo, String action,
                                   String screenshotKey, String screenshotUrl,
                                   String viewTreeKey, String viewTreeUrl,
                                   String logTailKey, String logTailUrl,
                                   String recordKey, String recordUrl,
                                   String netTimelineKey, String netTimelineUrl,
                                   String perfTimelineKey, String perfTimelineUrl) {
}
