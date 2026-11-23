package com.sixbean.testpike.core.spec.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecUrlFetchRequest;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
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
@RequestMapping("/internal/api-specs")
public class ApiSpecInternalController {

    private final ApiSpecService apiSpecService;

    public ApiSpecInternalController(ApiSpecService apiSpecService) {
        this.apiSpecService = apiSpecService;
    }

    
    @PostMapping
    public Response<ApiSpecInfo> upload(@RequestParam long projectId,
                                        @RequestParam String name,
                                        @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(apiSpecService.upload(
                OperatorContext.currentOperatorId(), projectId, name, file.getBytes(), null));
    }

    
    @GetMapping
    public Response<PageResult<ApiSpecInfo>> list(@RequestParam long projectId,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(apiSpecService.list(projectId, page, size));
    }

    
    @GetMapping("/history")
    public Response<List<ApiSpecInfo>> history(@RequestParam long projectId,
                                               @RequestParam String name) {
        return Response.builderSuccess(apiSpecService.history(projectId, name));
    }

 
    @PostMapping("/fetch-url")
    public Response<ApiSpecInfo> fetchUrl(@RequestBody SpecUrlFetchRequest request) {
        return Response.builderSuccess(apiSpecService.fetchFromUrl(
                OperatorContext.currentOperatorId(),
                request.projectId(), request.name(), request.url()));
    }

    
    @GetMapping("/{id}")
    public Response<ApiSpecInfo> get(@PathVariable long id) {
        return Response.builderSuccess(apiSpecService.get(id));
    }
}
