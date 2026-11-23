package com.sixbean.testpike.core.storage.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.storage.api.dto.StorageConfigInput;
import com.sixbean.testpike.core.storage.api.dto.StorageConfigView;
import com.sixbean.testpike.core.storage.api.dto.StorageProbeRequest;
import com.sixbean.testpike.core.storage.api.dto.StorageProbeResult;
import com.sixbean.testpike.core.storage.domain.service.StorageConfigService;
import com.sixbean.testpike.core.storage.domain.service.StorageProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/storage")
public class StorageConfigInternalController {

    private final StorageConfigService storageConfigService;
    private final StorageProbeService storageProbeService;

    public StorageConfigInternalController(StorageConfigService storageConfigService,
                                           StorageProbeService storageProbeService) {
        this.storageConfigService = storageConfigService;
        this.storageProbeService = storageProbeService;
    }

    @GetMapping("/config")
    public Response<StorageConfigView> get() {
        return Response.builderSuccess(storageConfigService.get());
    }

    @PutMapping("/config")
    public Response<StorageConfigView> update(@RequestBody StorageConfigInput input) {
        return Response.builderSuccess(
                storageConfigService.update(OperatorContext.currentOperatorId(), input));
    }

    
    @PostMapping("/probe")
    public Response<StorageProbeResult> probe(@RequestBody StorageProbeRequest request) {
        return Response.builderSuccess(storageProbeService.probe(request));
    }
}
