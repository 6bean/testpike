package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceThumbnailView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.admin.appauto.api.dto.DeviceSaveRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
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
@RequestMapping("/api/v1/appauto")
public class AppautoDeviceController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoDeviceController(CoreAppautoClient coreAppautoClient, ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @GetMapping("/devices")
    @RequirePermission("perm:project:project-view")
    public Response<List<AppautoDeviceView>> list(@RequestParam long projectId,
                                                  @RequestParam(required = false) String label) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.listDevices(projectId, label));
    }

    
    @PostMapping("/devices")
    @RequirePermission("perm:project:project-view")
    public Response<Long> register(@RequestBody DeviceSaveRequest request) {
        if (request.projectId() == null) {
            guard.assertSystemAdminOnly(UserContext.current(), "登记跨项目共享设备");
        } else {
            guard.assertCanManage(UserContext.current(), request.projectId());
        }
        return Response.builderSuccess(coreAppautoClient.registerDevice(request));
    }

    
    @GetMapping("/devices/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoDeviceView> get(@PathVariable long id) {
        AppautoDeviceView device = coreAppautoClient.findDevice(id);
        if (device.projectId() != null) {
            guard.assertCanView(UserContext.current(), device.projectId());
        }
        return Response.builderSuccess(device);
    }

    /**
     * Device-wall thumbnail cell (P1 wall live face). Permission mirrors the
     * detail endpoint above: resolve ownership from the device record first,
     * then the project view gate — no second verdict chain; shared-slot rows
     * (empty ownership) skip the project gate. Assembly, honest IDLE/NO_FRAME
     * cells and the plain-language 404 for unknown devices are single-sourced
     * in the core service; the bridge passes the view through untouched.
     */
    @GetMapping("/devices/{id}/thumbnail")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoDeviceThumbnailView> thumbnail(@PathVariable long id) {
        AppautoDeviceView device = coreAppautoClient.findDevice(id);
        if (device.projectId() != null) {
            guard.assertCanView(UserContext.current(), device.projectId());
        }
        return Response.builderSuccess(coreAppautoClient.deviceThumbnail(id));
    }

    
    @PutMapping("/devices/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> update(@PathVariable long id, @RequestBody DeviceSaveRequest request) {
        AppautoDeviceView current = coreAppautoClient.findDevice(id);
        assertWriteGate(UserContext.current(), current.projectId(), "编辑");
        coreAppautoClient.updateDevice(id, request);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/devices/{id}/unquarantine")
    @RequirePermission("perm:project:project-view")
    public Response<Void> unquarantine(@PathVariable long id) {
        AppautoDeviceView current = coreAppautoClient.findDevice(id);
        assertWriteGate(UserContext.current(), current.projectId(), "解除隔离");
        coreAppautoClient.unquarantineDevice(id);
        return Response.builderSuccess(null);
    }

    
    @DeleteMapping("/devices/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<Void> delete(@PathVariable long id) {
        AppautoDeviceView current = coreAppautoClient.findDevice(id);
        assertWriteGate(UserContext.current(), current.projectId(), "退役");
        coreAppautoClient.deleteDevice(id);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/devices/{id}/lease-runs")
    @RequirePermission("perm:project:project-view")
    public Response<List<AppautoLeaseRunView>> leaseRuns(@PathVariable long id) {
        AppautoDeviceView current = coreAppautoClient.findDevice(id);
        if (current.projectId() != null) {
            guard.assertCanView(UserContext.current(), current.projectId());
        }
        return Response.builderSuccess(coreAppautoClient.listDeviceLeaseRuns(id));
    }

    
    private void assertWriteGate(com.sixbean.testpike.admin.auth.api.dto.CurrentUser user,
                                 Long ownerProjectId, String action) {
        if (ownerProjectId == null) {
            guard.assertSystemAdminOnly(user, action + "跨项目共享设备");
        } else {
            guard.assertCanManage(user, ownerProjectId);
        }
    }
}
