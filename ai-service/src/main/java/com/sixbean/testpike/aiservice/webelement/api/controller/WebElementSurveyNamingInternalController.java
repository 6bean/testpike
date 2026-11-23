package com.sixbean.testpike.aiservice.webelement.api.controller;

import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingRequest;
import com.sixbean.testpike.aiservice.webelement.api.dto.SurveyNamingResult;
import com.sixbean.testpike.aiservice.webelement.domain.service.WebElementNamingService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Survey candidate naming internal endpoint (WE batch segment P3; the
 * VisualLocateInternalController family: /internal/** stays out of the 02 API
 * registry). Controller thin — switch / template / parse checks all live in
 * the service layer. Consumer = core relay (executor→api→core→here per the
 * service-layering rule: external agents never connect to ai-service).
 */
@RestController
@RequestMapping("/internal/web-element")
public class WebElementSurveyNamingInternalController {

    private final WebElementNamingService namingService;

    public WebElementSurveyNamingInternalController(WebElementNamingService namingService) {
        this.namingService = namingService;
    }

    /** Candidate pool → AI business-semantic names keyed by anchor identity. */
    @PostMapping("/survey-naming")
    public Response<SurveyNamingResult> nameSurveyCandidates(
            @RequestBody SurveyNamingRequest request) {
        return Response.builderSuccess(namingService.nameSurveyCandidates(request));
    }
}
