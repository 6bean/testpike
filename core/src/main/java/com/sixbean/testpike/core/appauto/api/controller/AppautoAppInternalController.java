package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPackageGrantView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAppService;
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
@RequestMapping("/internal/appauto")
public class AppautoAppInternalController {

    private final AppautoAppService appService;

    public AppautoAppInternalController(AppautoAppService appService) {
        this.appService = appService;
    }

    
    @PostMapping("/apps")
    public Response<Long> create(@RequestParam long projectId, @RequestBody AppautoAppInput input) {
        return Response.builderSuccess(appService.create(projectId, input));
    }

    
    @GetMapping("/apps/{id}")
    public Response<AppautoAppInfo> get(@PathVariable long id) {
        return Response.builderSuccess(appService.get(id));
    }

    
    @GetMapping("/apps")
    public Response<List<AppautoAppInfo>> list(@RequestParam long projectId) {
        return Response.builderSuccess(appService.list(projectId));
    }

    
    @PostMapping("/apps/{id}/package")
    public Response<String> uploadPackage(@PathVariable long id,
                                          @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(appService.uploadPackage(id, file.getBytes(),
                file.getOriginalFilename()));
    }

    
    @DeleteMapping("/apps/{id}")
    public Response<Void> delete(@PathVariable long id) {
        appService.delete(id);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/packages/download-grant")
    public Response<AppautoPackageGrantView> grantPackageDownload(
            @RequestBody java.util.Map<String, Object> body) {
        Object taskId = body.get("taskId");
        if (taskId == null) {
            throw new ServiceException("taskId 不能为空（下载授权按任务行反查归属）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return Response.builderSuccess(appService.grantPackageDownload(
                Long.parseLong(String.valueOf(taskId)), body.get("artifactRef")));
    }
}
