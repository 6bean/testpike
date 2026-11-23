package com.sixbean.testpike.core.script.api.controller;

import com.sixbean.testpike.core.script.api.dto.Flowchart;
import com.sixbean.testpike.core.script.api.dto.FlowchartPreviewInput;
import com.sixbean.testpike.core.script.api.dto.RepairSaveInput;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionDiff;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/scripts")
public class ScriptInternalController {

    private final ScriptService scriptService;

    public ScriptInternalController(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    
    @PostMapping
    public Response<ScriptInfo> save(@RequestBody ScriptSaveInput input) {
        return Response.builderSuccess(scriptService.save(input));
    }

    
    @PostMapping("/{caseId}/repaired")
    public Response<ScriptInfo> saveRepaired(@PathVariable long caseId,
                                             @RequestBody RepairSaveInput input) {
        return Response.builderSuccess(
                scriptService.saveRepaired(caseId, input.schemaVersion(), input.actionJson()));
    }

    
    @GetMapping("/{caseId}")
    public Response<ScriptInfo> getByCaseId(@PathVariable long caseId) {
        return Response.builderSuccess(scriptService.getByCaseId(caseId));
    }

    
    @PutMapping("/{caseId}/pending")
    public Response<Void> markPending(@PathVariable long caseId) {
        scriptService.markPending(caseId);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{caseId}/versions")
    public Response<List<ScriptVersionInfo>> versions(@PathVariable long caseId) {
        return Response.builderSuccess(scriptService.listVersions(caseId));
    }

    
    @GetMapping("/{caseId}/versions/{v}/diff")
    public Response<ScriptVersionDiff> versionDiff(@PathVariable long caseId, @PathVariable int v,
                                                   @RequestParam(required = false) Integer baseVersion) {
        return Response.builderSuccess(scriptService.diffVersion(caseId, v, baseVersion));
    }

    
    @GetMapping("/{caseId}/flowchart")
    public Response<Flowchart> flowchart(@PathVariable long caseId) {
        return Response.builderSuccess(scriptService.flowchart(caseId));
    }

    
    @PostMapping("/{caseId}/flowchart/preview")
    public Response<Flowchart> previewFlowchart(@PathVariable long caseId,
                                                @RequestBody FlowchartPreviewInput input) {
        return Response.builderSuccess(scriptService.previewFlowchart(caseId,
                input == null ? null : input.actionJson()));
    }
}
