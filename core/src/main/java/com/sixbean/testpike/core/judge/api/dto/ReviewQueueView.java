package com.sixbean.testpike.core.judge.api.dto;

import java.time.LocalDateTime;
import java.util.List;


public record ReviewQueueView(Long recordId, Long runId, Long sampleId,
                              Integer ruleScore, Integer finalScore,
                              List<String> missingDimensions, String queueReason,
                              LocalDateTime createdAt) {
}
