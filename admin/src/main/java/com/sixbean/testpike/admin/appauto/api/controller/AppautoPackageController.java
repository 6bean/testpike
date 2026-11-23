package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoAppView;
import com.sixbean.testpike.admin.appauto.api.dto.AppPackageSaveRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;


@RestController
@RequestMapping("/api/v1/appauto")
public class AppautoPackageController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;

    public AppautoPackageController(CoreAppautoClient coreAppautoClient, ProjectAccessGuard guard) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
    }

    
    @GetMapping("/packages")
    @RequirePermission("perm:appauto:pkg:view")
    public Response<List<AppautoAppView>> list(@RequestParam long projectId) {
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.listAppPackages(projectId));
    }

    
    @PostMapping("/packages")
    @RequirePermission("perm:appauto:pkg:manage")
    public Response<Long> create(@RequestBody AppPackageSaveRequest request) {
        guard.assertCanManage(UserContext.current(), request.projectId());
        return Response.builderSuccess(coreAppautoClient.createAppPackage(request));
    }

    
    @GetMapping("/packages/{id}")
    @RequirePermission("perm:appauto:pkg:view")
    public Response<AppautoAppView> get(@PathVariable long id) {
        AppautoAppView app = coreAppautoClient.findAppPackage(id);
        guard.assertCanView(UserContext.current(), app.projectId());
        return Response.builderSuccess(app);
    }

    
    @PostMapping("/packages/{id}/upload")
    @RequirePermission("perm:appauto:pkg:manage")
    public Response<String> upload(@PathVariable long id,
                                   @RequestPart("file") MultipartFile file) throws IOException {
        AppautoAppView current = coreAppautoClient.findAppPackage(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        return Response.builderSuccess(coreAppautoClient.uploadAppPackage(id,
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    
    @DeleteMapping("/packages/{id}")
    @RequirePermission("perm:appauto:pkg:manage")
    public Response<Void> delete(@PathVariable long id) {
        AppautoAppView current = coreAppautoClient.findAppPackage(id);
        guard.assertCanManage(UserContext.current(), current.projectId());
        coreAppautoClient.deleteAppPackage(id);
        return Response.builderSuccess(null);
    }
}
