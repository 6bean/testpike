package com.sixbean.testpike.core.repair.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairItem;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairRecordInput;
import com.sixbean.testpike.core.repair.api.dto.TrialRepairTaskInput;
import com.sixbean.testpike.core.repair.domain.service.ScriptRepairService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/script-repairs")
public class ScriptRepairInternalController {

    private final ScriptRepairService scriptRepairService;

    public ScriptRepairInternalController(ScriptRepairService scriptRepairService) {
        this.scriptRepairService = scriptRepairService;
    }

    
    @PostMapping
    public Response<ScriptRepairItem> record(@RequestBody ScriptRepairRecordInput input) {
        return Response.builderSuccess(scriptRepairService.record(input));
    }

    
    @GetMapping("/rounds")
    public Response<Long> rounds(@RequestParam long caseId) {
        return Response.builderSuccess(scriptRepairService.countRoundsWithin24h(caseId));
    }

    
    @PostMapping("/trial-task")
    public Response<Long> trialTask(@RequestBody TrialRepairTaskInput input) {
        return Response.builderSuccess(scriptRepairService.trialTask(input));
    }

    
    @GetMapping
    public Response<PageResult<ScriptRepairItem>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long caseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(scriptRepairService.list(projectId, caseId, page, size));
    }
}
