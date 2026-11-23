package com.sixbean.testpike.core.perf.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.perf.api.dto.PerfDatasetPageView;
import com.sixbean.testpike.core.perf.domain.service.PerfDatasetService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Internal HTTP door for the perf CSV dataset ledger (core service layer,
 * called by admin over HTTP; .
 * <p>Project-manager authorization is enforced on the admin side; this layer
 * is a thin wrapper — every validation rule (RFC4180 parse, identifier-safe
 * columns, K8 row budget, cross-project ownership) lives in
 * {@link PerfDatasetService} exactly once, and its human-language errors
 * surface verbatim through the global exception handler (no second gate here).
 */
@RestController
@RequestMapping("/internal/perf/datasets")
public class PerfDatasetInternalController {

    private final PerfDatasetService datasetService;

    public PerfDatasetInternalController(PerfDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    /** Upload + validate one CSV file for a project; returns the new dataset id. */
    @PostMapping
    public Response<Long> upload(@RequestParam long projectId,
                                 @RequestParam String name,
                                 @RequestPart("file") MultipartFile file) throws IOException {
        return Response.builderSuccess(datasetService.upload(projectId, name,
                file.getOriginalFilename(), file.getBytes()));
    }

    /** Paged dataset window of one project (clamp + whitelisted sort + truthful total). */
    @GetMapping("/page")
    public Response<PerfDatasetPageView> page(@RequestParam long projectId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              @RequestParam(required = false) String sortBy,
                                              @RequestParam(required = false) String sortDir,
                                              @RequestParam(defaultValue = "0") long programId) {
        return Response.builderSuccess(datasetService.listPaged(
                com.sixbean.testpike.core.audit.domain.component.OperatorContext.currentOperatorId(),
                programId, projectId, page, pageSize, sortBy, sortDir));
    }

    /** Dataset detail with the first N preview rows streamed from object storage. */
    @GetMapping("/{id}")
    public Response<Map<String, Object>> view(@RequestParam long projectId, @PathVariable long id) {
        return Response.builderSuccess(datasetService.view(projectId, id));
    }

    /** Engine × strategy matrix for the wizard's honest-disable UI (policy single source). */
    @GetMapping("/alloc-policy")
    public Response<Map<String, Object>> allocPolicy() {
        return Response.builderSuccess(datasetService.allocPolicy());
    }

    /** Delete the ledger row and its stored object (cross-project id refused 40401). */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@RequestParam long projectId, @PathVariable long id) {
        datasetService.delete(projectId, id);
        return Response.builderSuccess(null);
    }
}
