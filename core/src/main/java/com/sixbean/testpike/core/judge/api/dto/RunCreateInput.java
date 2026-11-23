package com.sixbean.testpike.core.judge.api.dto;


public record RunCreateInput(Long rubricId, String evalsetName, Integer evalsetVersion,
                             String evalsetDigest, String targetType, String targetRef,
                             Long llmConfigId, Long createdBy) {
}
