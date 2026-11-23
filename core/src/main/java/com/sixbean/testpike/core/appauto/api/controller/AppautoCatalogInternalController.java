package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineAvailabilityInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineHealthInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineUpdateInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformUpdateInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPortabilityInfo;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAvailabilityService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvDoctorService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPlatformService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPortabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoCatalogInternalController {

    private final AppautoEngineService engineService;
    private final AppautoPlatformService platformService;
    private final AppautoAvailabilityService availabilityService;
    private final AppautoEnvDoctorService envDoctorService;
    private final AppautoPortabilityService portabilityService;

    public AppautoCatalogInternalController(AppautoEngineService engineService,
                                            AppautoPlatformService platformService,
                                            AppautoAvailabilityService availabilityService,
                                            AppautoEnvDoctorService envDoctorService,
                                            AppautoPortabilityService portabilityService) {
        this.engineService = engineService;
        this.platformService = platformService;
        this.availabilityService = availabilityService;
        this.envDoctorService = envDoctorService;
        this.portabilityService = portabilityService;
    }

    
    @GetMapping("/engines/availability")
    public Response<List<AppautoEngineAvailabilityInfo>> listEngineAvailability(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(availabilityService.availability(includeDisabled));
    }

    
    @GetMapping("/engines/health")
    public Response<List<AppautoEngineHealthInfo>> listEngineHealth(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(envDoctorService.health(includeDisabled));
    }

    
    @GetMapping("/engines")
    public Response<List<AppautoEngineInfo>> listEngines(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(engineService.listEngines(includeDisabled));
    }

    
    @GetMapping("/portability")
    public Response<AppautoPortabilityInfo> precheckPortability(
            @RequestParam long projectId, @RequestParam long caseId,
            @RequestParam(required = false) String engineCode) {
        return Response.builderSuccess(portabilityService.precheck(projectId, caseId, engineCode));
    }

    
    @GetMapping("/engines/{code}")
    public Response<AppautoEngineInfo> getEngine(@PathVariable String code) {
        return Response.builderSuccess(engineService.getByCode(code));
    }

    
    @GetMapping("/platforms")
    public Response<List<AppautoPlatformInfo>> listPlatforms(
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return Response.builderSuccess(platformService.listPlatforms(includeDisabled));
    }

    
    @GetMapping("/platforms/{code}")
    public Response<AppautoPlatformInfo> getPlatform(@PathVariable String code) {
        return Response.builderSuccess(platformService.getByCode(code));
    }

    
    @PutMapping("/engines/{code}")
    public Response<Void> updateEngine(@PathVariable String code,
                                       @RequestBody AppautoEngineUpdateInput input) {
        engineService.update(code, input);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/platforms/{code}")
    public Response<Void> updatePlatform(@PathVariable String code,
                                         @RequestBody AppautoPlatformUpdateInput input) {
        platformService.update(code, input);
        return Response.builderSuccess(null);
    }
}
