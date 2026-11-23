package com.sixbean.testpike.core.judge.api.dto;


public record JudgeReviewInput(Long recordId, Long reviewer, String dimension,
                               Integer newScore, String reason) {
}
