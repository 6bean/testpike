package com.sixbean.testpike.core.plugin.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.plugin.api.dto.AppliedPluginView;
import com.sixbean.testpike.core.plugin.api.dto.PluginApplyInput;
import com.sixbean.testpike.core.plugin.api.dto.PluginApplyView;
import com.sixbean.testpike.core.plugin.domain.service.PluginService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/projects")
public class ProjectPluginInternalController {

    private final PluginService pluginService;

    public ProjectPluginInternalController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    
    @PostMapping("/{projectId}/plugins/{pluginId}/apply")
    public Response<PluginApplyView> apply(@PathVariable long projectId,
                                           @PathVariable long pluginId,
                                           @RequestBody(required = false) PluginApplyInput input) {
        return Response.builderSuccess(
                pluginService.apply(OperatorContext.currentOperatorId(), projectId, pluginId,
                        input != null && input.confirm()));
    }

    
    @DeleteMapping("/{projectId}/plugins/{pluginId}")
    public Response<Void> uninstall(@PathVariable long projectId,
                                    @PathVariable long pluginId) {
        pluginService.uninstall(OperatorContext.currentOperatorId(), projectId, pluginId);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/{projectId}/plugins")
    public Response<List<AppliedPluginView>> listApplied(@PathVariable long projectId) {
        return Response.builderSuccess(pluginService.listApplied(projectId));
    }
}
