package com.sixbean.testpike.core.judge.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.judge.api.dto.CalibrationGroupView;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewView;
import com.sixbean.testpike.core.judge.api.dto.ReviewQueueView;
import com.sixbean.testpike.core.judge.domain.service.JudgeCalibrationProfileService;
import com.sixbean.testpike.core.judge.domain.service.JudgeReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/judge")
public class JudgeReviewInternalController {

    private final JudgeReviewService reviewService;
    private final JudgeCalibrationProfileService profileService;

    public JudgeReviewInternalController(JudgeReviewService reviewService,
                                         JudgeCalibrationProfileService profileService) {
        this.reviewService = reviewService;
        this.profileService = profileService;
    }

    
    @PostMapping("/review")
    public Response<JudgeReviewView> review(@RequestBody JudgeReviewInput input) {
        if (input == null) {
            throw new ValidationException("改判请求体不能为空（需 recordId/reviewer/newScore）");
        }
        return Response.builderSuccess(reviewService.recordReview(input));
    }

    
    @GetMapping("/reviews")
    public Response<List<JudgeReviewView>> reviews(@RequestParam Long recordId) {
        return Response.builderSuccess(reviewService.listReviews(recordId));
    }

    
    @GetMapping("/review-queue")
    public Response<List<ReviewQueueView>> reviewQueue(@RequestParam(required = false) Long runId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(reviewService.reviewQueue(runId, page, size));
    }

    
    @GetMapping("/calibration/profile")
    public Response<List<CalibrationGroupView>> calibrationProfile(
            @RequestParam(required = false) String rubricName,
            @RequestParam(required = false) String rubricDomain,
            @RequestParam(required = false) Long llmConfigId) {
        return Response.builderSuccess(profileService.profile(rubricName, rubricDomain, llmConfigId));
    }
}
