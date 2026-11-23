package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterComponentView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreClusterComponentClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/cluster")
public class ClusterComponentController {

    private final CoreClusterComponentClient coreClusterComponentClient;

    public ClusterComponentController(CoreClusterComponentClient coreClusterComponentClient) {
        this.coreClusterComponentClient = coreClusterComponentClient;
    }

    
    @GetMapping("/components")
    @RequirePermission("perm:project:executor-pool")
    public Response<List<ClusterComponentView>> list() {
        return Response.builderSuccess(coreClusterComponentClient.listComponents());
    }
}
