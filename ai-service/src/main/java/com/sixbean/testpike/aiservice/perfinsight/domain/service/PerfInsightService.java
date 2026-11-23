package com.sixbean.testpike.aiservice.perfinsight.domain.service;

import com.sixbean.testpike.aiservice.perfinsight.api.dto.PerfInsightRequest;

import java.util.Map;

/** Explains a finished load-test run's attribution data in plain words (B-11, ai-service leg). */
public interface PerfInsightService {

    /**
     * Turn the request's facts block into a short plain-word read-out.
     *
     * @return {@code {"insight":"..."}} — a transient read-out, never persisted here
     */
    Map<String, Object> explain(PerfInsightRequest request);
}
