package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleCacheHitRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAiSampleRecordRequest;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAiSampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/appauto/ai-samples")
public class AppautoAiSampleInternalController {

    private final AppautoAiSampleService aiSampleService;

    public AppautoAiSampleInternalController(AppautoAiSampleService aiSampleService) {
        this.aiSampleService = aiSampleService;
    }

    
    @PostMapping("/cache-hit")
    public Response<Map<String, Object>> cacheHit(
            @RequestBody AppautoAiSampleCacheHitRequest request) {
        return Response.builderSuccess(aiSampleService.cacheHitForExecutor(request));
    }

    
    @PostMapping("/record")
    public Response<Map<String, Object>> record(
            @RequestBody AppautoAiSampleRecordRequest request) {
        long id = aiSampleService.recordFromExecutor(request);
        return Response.builderSuccess(Map.of("sampleId", id));
    }

    
    @GetMapping
    public Response<List<Map<String, Object>>> listSamples(
            @RequestParam long projectId,
            @RequestParam long caseId,
            @RequestParam(defaultValue = "100") int limit) {
        return Response.builderSuccess(
                aiSampleService.listSamples(projectId, caseId, limit));
    }

    
    @GetMapping("/hit-rate")
    public Response<Map<String, Object>> hitRateStats(
            @RequestParam long projectId,
            @RequestParam long caseId) {
        return Response.builderSuccess(aiSampleService.hitRateStats(projectId, caseId));
    }
}
