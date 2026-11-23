package com.sixbean.testpike.admin.explore.api.dto;


public record ExploreStepView(Integer seq, String stepAction, String stepResult,
                              String screenshotKey, String domSnapshotKey, String llmThinkMd) {
}
