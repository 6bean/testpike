package com.sixbean.testpike.core.usage.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.usage.api.dto.UsageCalibrationInfo;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInput;
import com.sixbean.testpike.core.usage.api.dto.UsageSampleInfo;
import com.sixbean.testpike.core.usage.domain.service.UsageCalibrationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/llm-usage")
public class UsageInternalController {

    private final UsageCalibrationService usageCalibrationService;

    public UsageInternalController(UsageCalibrationService usageCalibrationService) {
        this.usageCalibrationService = usageCalibrationService;
    }

    
    @PostMapping("/samples")
    public Response<Void> record(@RequestBody UsageSampleInput input) {
        usageCalibrationService.record(input);
        return Response.builderSuccess(null);
    }

    
    @GetMapping("/calibration")
    public Response<List<UsageCalibrationInfo>> calibration(
            @RequestParam(defaultValue = "7") int days) {
        return Response.builderSuccess(usageCalibrationService.calibration(days));
    }

    /**
 * The optional sort pair reaches the core column whitelist (single gate). */
    @GetMapping("/samples")
    public Response<PageResult<UsageSampleInfo>> detail(
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String chain,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                usageCalibrationService.detailPage(model, chain, keyword, page, size, sortBy, sortDir));
    }
}
