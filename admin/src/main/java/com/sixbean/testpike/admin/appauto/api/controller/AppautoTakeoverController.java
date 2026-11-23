package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.admin.appauto.api.dto.TakeoverCreateRequest;
import com.sixbean.testpike.admin.appauto.api.dto.TakeoverInputEnqueueRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/api/v1/appauto/takeover")
public class AppautoTakeoverController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoTakeoverController(CoreAppautoClient coreAppautoClient,
                                     ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @PostMapping("/request")
    @RequirePermission("perm:appauto:watch:takeover")
    public Response<AppautoTakeoverView> request(@RequestBody TakeoverCreateRequest req) {
        AppautoDeviceView device = coreAppautoClient.findDevice(req.deviceId());
        if (device.projectId() != null) {
            guard.assertCanView(UserContext.current(), device.projectId());
        }
        return Response.builderSuccess(coreAppautoClient.requestTakeover(req.deviceId(),
                req.taskId(), req.reason(), UserContext.currentUserId()));
    }

    
    @PostMapping("/{id}/agree")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoTakeoverView> agree(@PathVariable long id) {
        AppautoTakeoverView row = coreAppautoClient.findTakeover(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(coreAppautoClient.agreeTakeover(id,
                UserContext.currentUserId()));
    }

    
    @PostMapping("/{id}/reject")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoTakeoverView> reject(@PathVariable long id) {
        AppautoTakeoverView row = coreAppautoClient.findTakeover(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(coreAppautoClient.rejectTakeover(id,
                UserContext.currentUserId()));
    }

    
    @PostMapping("/{id}/force")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoTakeoverView> force(@PathVariable long id) {
        guard.assertSystemAdminOnly(UserContext.current(), "强制接管设备会话");
        return Response.builderSuccess(coreAppautoClient.forceTakeover(id,
                UserContext.currentUserId()));
    }

    
    @PostMapping("/{id}/end")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoTakeoverView> end(@PathVariable long id) {
        AppautoTakeoverView row = coreAppautoClient.findTakeover(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(coreAppautoClient.endTakeover(id,
                UserContext.currentUserId()));
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:project:project-view")
    public Response<AppautoTakeoverView> get(@PathVariable long id) {
        AppautoTakeoverView row = coreAppautoClient.findTakeover(id);
        guard.assertCanView(UserContext.current(), row.projectId());
        return Response.builderSuccess(row);
    }

    
    @PostMapping("/{id}/input")
    @RequirePermission("perm:appauto:watch:takeover")
    public Response<Map<String, Object>> enqueueInput(@PathVariable long id,
                                                      @RequestBody
                                                      TakeoverInputEnqueueRequest req) {
        return Response.builderSuccess(coreAppautoClient.enqueueTakeoverInput(id,
                UserContext.currentUserId(), req.commands()));
    }
}
