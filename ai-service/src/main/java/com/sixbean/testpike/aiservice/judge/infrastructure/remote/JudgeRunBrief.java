package com.sixbean.testpike.aiservice.judge.infrastructure.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 *
 * @param id tp_judge_run.id
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeRunBrief(Long id, String evalsetName, Integer evalsetVersion,
                            String evalsetDigest, Long llmConfigId, Integer status,
                            Map<String, Object> rubricSnapshot, String targetType,
                            String targetRef) {
}
