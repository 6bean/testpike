package com.sixbean.testpike.admin.imports.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.imports.api.dto.ImportResultView;
import com.sixbean.testpike.admin.imports.infrastructure.remote.client.CoreImportClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;


@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

    private final CoreImportClient coreImportClient;
    private final ProjectAccessGuard guard;

    public ImportController(CoreImportClient coreImportClient, ProjectAccessGuard guard) {
        this.coreImportClient = coreImportClient;
        this.guard = guard;
    }

    
    @PostMapping("/cases")
    public Response<ImportResultView> importCases(@RequestParam long projectId,
                                                  @RequestParam(required = false) Integer targetStatus,
                                                  @RequestPart("file") MultipartFile file) throws IOException {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, projectId);
        return Response.builderSuccess(coreImportClient.importCases(projectId, targetStatus,
                file.getBytes(), file.getOriginalFilename()));
    }

    
    @PostMapping("/cases/retry")
    public Response<ImportResultView> retryFailedCases(@RequestParam long projectId,
                                                       @RequestParam(required = false) Integer targetStatus,
                                                       @RequestParam List<Integer> retryRows,
                                                       @RequestPart("file") MultipartFile file) throws IOException {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteCase(user, projectId);
        return Response.builderSuccess(coreImportClient.retryFailedCases(projectId, targetStatus,
                retryRows, file.getBytes(), file.getOriginalFilename()));
    }

    
    @PostMapping("/requirements")
    public Response<ImportResultView> importRequirements(@RequestParam long projectId,
                                                         @RequestPart("file") MultipartFile file) throws IOException {
        CurrentUser user = UserContext.current();
        guard.assertCanWriteRequirement(user, projectId);
        return Response.builderSuccess(coreImportClient.importRequirements(projectId,
                file.getBytes(), file.getOriginalFilename()));
    }

    
    @GetMapping("/templates/cases")
    public Response<byte[]> caseTemplate() {
        return Response.builderSuccess(coreImportClient.caseTemplate());
    }

    
    @GetMapping("/templates/requirements")
    public Response<byte[]> requirementTemplate() {
        return Response.builderSuccess(coreImportClient.requirementTemplate());
    }
}
