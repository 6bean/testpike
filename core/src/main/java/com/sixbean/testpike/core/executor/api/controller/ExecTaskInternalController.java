package com.sixbean.testpike.core.executor.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.executor.api.dto.AiClassifyInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/exec-tasks")
public class ExecTaskInternalController {

    private final ExecTaskService execTaskService;

    public ExecTaskInternalController(ExecTaskService execTaskService) {
        this.execTaskService = execTaskService;
    }

    
    @PostMapping("/{taskId}/ai-classify")
    public Response<Void> aiClassify(@PathVariable long taskId,
                                     @RequestBody AiClassifyInput input) {
        execTaskService.updateAiClassify(taskId, input.aiFailureClass(), input.aiFailureNote());
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/case/{caseId}/recent-classify")
    public Response<List<Map<String, Object>>> recentClassify(@PathVariable long caseId) {
        return Response.builderSuccess(execTaskService.listRecentClassify(caseId));
    }

    
    @GetMapping("/{taskId}/screenshot-url")
    public Response<String> screenshotUrl(@PathVariable long taskId,
                                          @RequestParam String objectKey) {
        return Response.builderSuccess(execTaskService.screenshotViewUrl(taskId, objectKey));
    }
}
