package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewView;
import com.sixbean.testpike.core.judge.api.dto.ReviewQueueView;

import java.util.List;


public interface JudgeReviewService {

    
    JudgeReviewView recordReview(JudgeReviewInput input);

    
    List<JudgeReviewView> listReviews(Long recordId);

    
    List<ReviewQueueView> reviewQueue(Long runId, int page, int size);
}
