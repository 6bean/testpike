package com.sixbean.testpike.admin.sample.api.controller;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.sample.api.dto.TrainingSampleView;
import com.sixbean.testpike.admin.sample.infrastructure.remote.client.TrainingSampleCoreClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/training-samples")
public class TrainingSampleController {

    private final TrainingSampleCoreClient coreClient;

    public TrainingSampleController(TrainingSampleCoreClient coreClient) {
        this.coreClient = coreClient;
    }

    /**
 * The sort pair is forwarded to the core whitelist gate without a second gate. */
    @RequirePermission("perm:platform:data-mask")
    @GetMapping
    public Response<PageView<TrainingSampleView>> list(@RequestParam(required = false) Long projectId,
                                                       @RequestParam(required = false) String sampleType,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size,
                                                       @RequestParam(required = false) String sortBy,
                                                       @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                coreClient.listSamples(projectId, sampleType, page, size, sortBy, sortDir));
    }

    
    @RequirePermission("perm:platform:data-mask")
    @PutMapping("/{id}/adoption")
    public Response<Void> updateAdoption(@PathVariable Long id, @RequestParam int adopted) {
        coreClient.updateAdoption(id, adopted);
        return Response.builderSuccess(null);
    }
}
