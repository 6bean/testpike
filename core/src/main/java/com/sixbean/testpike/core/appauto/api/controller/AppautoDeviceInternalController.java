package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceService;
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
public class AppautoDeviceInternalController {

    private final AppautoDeviceService deviceService;

    public AppautoDeviceInternalController(AppautoDeviceService deviceService) {
        this.deviceService = deviceService;
    }

    
    @PostMapping("/devices")
    public Response<Long> register(@RequestParam(required = false) Long projectId,
                                   @RequestBody AppautoDeviceInput input) {
        return Response.builderSuccess(deviceService.register(projectId, input));
    }

    
    @PutMapping("/devices/{id}")
    public Response<Void> update(@PathVariable long id, @RequestBody AppautoDeviceInput input) {
        deviceService.update(id, input);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/devices/{id}")
    public Response<AppautoDeviceInfo> get(@PathVariable long id) {
        return Response.builderSuccess(deviceService.get(id));
    }

    
    @GetMapping("/devices")
    public Response<List<AppautoDeviceInfo>> list(@RequestParam long projectId,
                                                  @RequestParam(required = false) String label) {
        return Response.builderSuccess(deviceService.list(projectId, label));
    }

    
    @PostMapping("/devices/{id}/unquarantine")
    public Response<Void> unquarantine(@PathVariable long id) {
        deviceService.unquarantine(id);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/devices/{id}")
    public Response<Void> delete(@PathVariable long id) {
        deviceService.delete(id);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/devices/{id}/lease")
    public Response<Void> acquireLease(@PathVariable long id,
                                       @RequestParam(required = false) Long projectId,
                                       @RequestParam(required = false) Long batchId,
                                       @RequestParam String holderExecutorId) {
        deviceService.acquireLease(id, projectId, batchId, holderExecutorId);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/devices/{id}/lease/release")
    public Response<Void> releaseLease(@PathVariable long id,
                                       @RequestParam String holderExecutorId,
                                       @RequestParam(required = false) String reason) {
        deviceService.releaseLease(id, holderExecutorId, reason);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/devices/{id}/lease/renew")
    public Response<java.util.Map<String, Object>> renewLease(@PathVariable long id,
                                                              @RequestParam String holderExecutorId) {
        return Response.builderSuccess(
                java.util.Map.of("renewed", deviceService.renewLease(id, holderExecutorId)));
    }

 
    @GetMapping("/devices/{id}/lease-runs")
    public Response<List<AppautoLeaseRunView>> listLeaseRuns(@PathVariable long id) {
        return Response.builderSuccess(deviceService.listLeaseRuns(id));
    }

    
    @PostMapping("/devices/lease/sweep-expired")
    public Response<Integer> sweepExpiredLeases() {
        return Response.builderSuccess(deviceService.sweepExpiredDeviceLeases());
    }
}
