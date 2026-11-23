package com.sixbean.testpike.core.plugin.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.plugin.api.dto.AppliedProjectView;
import com.sixbean.testpike.core.plugin.api.dto.PluginEnableInput;
import com.sixbean.testpike.core.plugin.api.dto.PluginRegisterInput;
import com.sixbean.testpike.core.plugin.api.dto.PluginUpdateInput;
import com.sixbean.testpike.core.plugin.api.dto.PluginView;
import com.sixbean.testpike.core.plugin.domain.service.PluginService;
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
@RequestMapping("/internal/plugins")
public class PluginInternalController {

    private final PluginService pluginService;

    public PluginInternalController(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    
    @GetMapping
    public Response<List<PluginView>> list(@RequestParam(required = false) String industry,
                                           @RequestParam(required = false) Boolean enabled) {
        return Response.builderSuccess(pluginService.list(industry, enabled));
    }

    
    @PostMapping
    public Response<PluginView> register(@RequestBody PluginRegisterInput input) {
        return Response.builderSuccess(
                pluginService.register(OperatorContext.currentOperatorId(),
                        input == null ? null : input.content()));
    }

    
    @PutMapping("/{id}/enable")
    public Response<PluginView> setEnabled(@PathVariable long id,
                                           @RequestBody PluginEnableInput input) {
        return Response.builderSuccess(
                pluginService.setEnabled(OperatorContext.currentOperatorId(), id, input.enabled()));
    }

    
    @GetMapping("/{id}")
    public Response<PluginView> detail(@PathVariable long id) {
        return Response.builderSuccess(pluginService.detail(id));
    }

    
    @PutMapping("/{id}")
    public Response<PluginView> update(@PathVariable long id,
                                       @RequestBody PluginUpdateInput input) {
        return Response.builderSuccess(
                pluginService.update(OperatorContext.currentOperatorId(), id,
                        input == null ? null : input.revision(),
                        input == null ? null : input.content()));
    }

    
    @GetMapping("/{id}/applied-projects")
    public Response<List<AppliedProjectView>> appliedProjects(@PathVariable long id) {
        return Response.builderSuccess(pluginService.appliedProjects(id));
    }
}
