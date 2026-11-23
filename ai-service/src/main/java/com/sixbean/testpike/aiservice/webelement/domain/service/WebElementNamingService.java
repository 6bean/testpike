package com.sixbean.testpike.aiservice.webelement.domain.service;

import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingRequest;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingResult;

/**
 * AI semantic naming for page-survey candidates (WE batch segment P3).
 * Service interface / Impl split per the framework layering rules.
 */
public interface WebElementNamingService {

    /**
     * Name the candidate pool of one surveyed page in a single model round.
     * Never throws on provider trouble: an honest applied=false (possibly
     * empty) answer is returned instead, and every model burn — successful
     * or failed — leaves exactly one tp_ai_call_log row.
     */
    SurveyNamingResult nameSurveyCandidates(SurveyNamingRequest request);
}
