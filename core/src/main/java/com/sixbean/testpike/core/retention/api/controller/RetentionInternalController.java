package com.sixbean.testpike.core.retention.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.retention.api.dto.RetentionItem;
import com.sixbean.testpike.core.retention.domain.service.RetentionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/configs/retention")
public class RetentionInternalController {

    private final RetentionService retentionService;

    public RetentionInternalController(RetentionService retentionService) {
        this.retentionService = retentionService;
    }

    
    @PostMapping("/preview")
    public Response<List<RetentionItem>> preview() {
        return Response.builderSuccess(retentionService.preview());
    }

    
    @PostMapping("/run")
    public Response<List<RetentionItem>> run() {
        return Response.builderSuccess(retentionService.run());
    }
}
