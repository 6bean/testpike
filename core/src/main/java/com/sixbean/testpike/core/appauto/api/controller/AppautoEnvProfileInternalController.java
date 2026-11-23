package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/internal/appauto")
public class AppautoEnvProfileInternalController {

    private final AppautoEnvProfileService envProfileService;

    public AppautoEnvProfileInternalController(AppautoEnvProfileService envProfileService) {
        this.envProfileService = envProfileService;
    }

    
    @PostMapping("/env-profiles")
    public Response<Long> create(@RequestParam long projectId,
                                 @RequestBody AppautoEnvProfileInput input) {
        return Response.builderSuccess(envProfileService.create(projectId, input));
    }

    
    @PutMapping("/env-profiles/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody AppautoEnvProfileInput input) {
        envProfileService.update(id, input);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/env-profiles/{id}")
    public Response<AppautoEnvProfileInfo> get(@PathVariable long id) {
        return Response.builderSuccess(envProfileService.get(id));
    }

    
    @GetMapping("/env-profiles")
    public Response<List<AppautoEnvProfileInfo>> list(@RequestParam long projectId,
                                                      @RequestParam(required = false)
                                                      String profileKind) {
        return Response.builderSuccess(envProfileService.list(projectId, profileKind));
    }

    
    @DeleteMapping("/env-profiles/{id}")
    public Response<Void> delete(@PathVariable long id) {
        envProfileService.delete(id);
        return Response.builderSuccess(null);
    }
}
