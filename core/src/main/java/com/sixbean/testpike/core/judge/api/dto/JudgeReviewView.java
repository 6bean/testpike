package com.sixbean.testpike.core.judge.api.dto;

import java.time.LocalDateTime;


public record JudgeReviewView(Long id, Long runId, Long recordId, Long reviewer,
                              String dimension, Integer orgScore, Integer newScore,
                              String reason, LocalDateTime createdAt) {
}
