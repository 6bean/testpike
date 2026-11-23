package com.sixbean.testpike.admin.usage.api.controller;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.usage.api.dto.UsageCalibrationView;
import com.sixbean.testpike.admin.usage.api.dto.UsageSampleView;
import com.sixbean.testpike.admin.usage.infrastructure.remote.client.RestCoreUsageClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/llm-usage")
public class UsageCalibrationController {

    private final RestCoreUsageClient usageClient;

    public UsageCalibrationController(RestCoreUsageClient usageClient) {
        this.usageClient = usageClient;
    }

    
    @GetMapping("/calibration")
    @RequirePermission("perm:system:system-config")
    public Response<List<UsageCalibrationView>> calibration(
            @RequestParam(defaultValue = "7") int days) {
        return Response.builderSuccess(usageClient.calibration(days));
    }

    /**
 * The optional sort pair forwards to the core column whitelist (no second gate). */
    @GetMapping("/samples")
    @RequirePermission("perm:system:system-config")
    public Response<PageView<UsageSampleView>> samples(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        var result = usageClient.detail(model, chain, keyword, page, size, sortBy, sortDir);
        return Response.builderSuccess(new PageView<>(result.list(), result.total()));
    }
}
