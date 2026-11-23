package com.sixbean.testpike.core.tool.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingInput;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolView;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolConfirmTicket;
import com.sixbean.testpike.core.tool.api.dto.ToolFcAssemblyItem;
import com.sixbean.testpike.core.tool.api.dto.ToolDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolDownloadFile;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationQuery;
import com.sixbean.testpike.core.tool.api.dto.ToolInvokeInput;
import com.sixbean.testpike.core.tool.api.dto.ToolQuery;
import com.sixbean.testpike.core.tool.api.dto.ToolRejectInput;
import com.sixbean.testpike.core.tool.api.dto.ToolRollbackInput;
import com.sixbean.testpike.core.tool.api.dto.ToolTestRunInput;
import com.sixbean.testpike.core.tool.api.dto.ToolTestRunResult;
import com.sixbean.testpike.core.tool.api.dto.ToolUpdateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionInfo;
import com.sixbean.testpike.core.tool.domain.service.ProjectToolService;
import com.sixbean.testpike.core.tool.domain.service.ToolFcAssemblyService;
import com.sixbean.testpike.core.tool.domain.service.ToolExecutionService;
import com.sixbean.testpike.core.tool.domain.service.ToolInvocationService;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;


@RestController
@RequestMapping("/internal/tools")
public class ToolInternalController {

    private final ToolService toolService;
    private final ProjectToolService projectToolService;
    private final ToolInvocationService toolInvocationService;
    private final ToolExecutionService toolExecutionService;
    private final ToolFcAssemblyService toolFcAssemblyService;

    public ToolInternalController(ToolService toolService,
                                  ProjectToolService projectToolService,
                                  ToolInvocationService toolInvocationService,
                                  ToolExecutionService toolExecutionService,
                                  ToolFcAssemblyService toolFcAssemblyService) {
        this.toolService = toolService;
        this.projectToolService = projectToolService;
        this.toolInvocationService = toolInvocationService;
        this.toolExecutionService = toolExecutionService;
        this.toolFcAssemblyService = toolFcAssemblyService;
    }

    
    @GetMapping
    public Response<PageResult<ToolInfo>> list(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Integer type,
                                               @RequestParam(required = false) Integer status,
                                               @RequestParam(required = false) Long projectId,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) Long tagId,
                                               @RequestParam(required = false) String sortBy,
                                               @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(toolService.list(
                new ToolQuery(page, size, keyword, type, status, projectId, category, tagId,
                        sortBy, sortDir)));
    }

    
    @PostMapping
    public Response<ToolInfo> create(@RequestBody ToolCreateInput input) {
        return Response.builderSuccess(
                toolService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @GetMapping("/{id}")
    public Response<ToolInfo> get(@PathVariable long id) {
        return Response.builderSuccess(toolService.get(id));
    }

    
    @PutMapping("/{id}")
    public Response<ToolInfo> update(@PathVariable long id, @RequestBody ToolUpdateInput input) {
        return Response.builderSuccess(
                toolService.update(OperatorContext.currentOperatorId(), id, input));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id, @RequestParam int expectedVersion) {
        toolService.delete(OperatorContext.currentOperatorId(), id, expectedVersion);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/{id}/submit")
    public Response<ToolInfo> submit(@PathVariable long id, @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                toolService.submit(OperatorContext.currentOperatorId(), id, expectedVersion));
    }

    
    @PostMapping("/{id}/approve")
    public Response<ToolInfo> approve(@PathVariable long id, @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                toolService.approve(OperatorContext.currentOperatorId(), id, expectedVersion));
    }

    
    @PostMapping("/{id}/reject")
    public Response<ToolInfo> reject(@PathVariable long id, @RequestBody ToolRejectInput input) {
        return Response.builderSuccess(
                toolService.reject(OperatorContext.currentOperatorId(), id,
                        input.expectedVersion(), input.reason()));
    }

    
    @PostMapping("/{id}/retire")
    public Response<ToolInfo> retire(@PathVariable long id, @RequestParam int expectedVersion) {
        return Response.builderSuccess(
                toolService.retire(OperatorContext.currentOperatorId(), id, expectedVersion));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    @GetMapping("/project/{pid}")
    public Response<ProjectToolView> projectTools(@PathVariable long pid) {
        return Response.builderSuccess(projectToolService.getBindings(pid));
    }

    
    @PutMapping("/project/{pid}")
    public Response<ProjectToolView> saveProjectTools(@PathVariable long pid,
                                                      @RequestBody ProjectToolBindingInput input) {
        return Response.builderSuccess(projectToolService.saveBindings(
                OperatorContext.currentOperatorId(), pid, input));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    @GetMapping("/{id}/invocations")
    public Response<PageResult<ToolInvocationInfo>> invocations(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return Response.builderSuccess(toolInvocationService.list(
                new ToolInvocationQuery(page, size, id, start, end)));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    @PostMapping("/{id}/test-run")
    public Response<ToolTestRunResult> testRun(@PathVariable long id,
                                               @RequestBody(required = false) ToolTestRunInput input) {
        return Response.builderSuccess(toolExecutionService.testRun(
                OperatorContext.currentOperatorId(), id,
                input == null ? null : input.input()));
    }

    
    @PostMapping("/{id}/invoke")
    public Response<ToolTestRunResult> invoke(@PathVariable long id,
                                              @RequestBody(required = false) ToolInvokeInput input) {
        return Response.builderSuccess(toolExecutionService.invoke(
                OperatorContext.currentOperatorId(),
                input == null ? null : input.taskId(), id,
                input == null ? null : input.input(),
                input == null ? null : input.confirmToken(),
                input == null ? null : input.assertedProjectId()));
    }

    
    @PostMapping("/{id}/confirm-token")
    public Response<ToolConfirmTicket> confirmToken(@PathVariable long id) {
        return Response.builderSuccess(toolExecutionService.issueConfirmToken(
                OperatorContext.currentOperatorId(), id));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    // X-Operator-Id present drives the actor-aware five-way gate overload .
    @GetMapping("/project/{pid}/assembly")
    public Response<List<ToolFcAssemblyItem>> projectFcAssembly(@PathVariable long pid) {
        return Response.builderSuccess(toolFcAssemblyService.getAssemblyItems(
                pid, OperatorContext.currentOperatorId()));
    }

    /**
 * Draft-card routing facts of the project segment (tool-surface clearing
 * batch stage 2; 02 §2.16). ai-service ChatRunOrchestrator consults
 * this after a finished turn instead of matching tool-name literals: a
 * published write tool that declares draftObjectType in its exec spec
 * grows confirmable cards with zero orchestrator change. Visibility =
 * same single-sourced published∩enabled∪system-level read as assembly.
 */
    @GetMapping("/project/{pid}/draft-kinds")
    public Response<List<com.sixbean.testpike.core.tool.api.dto.ToolDraftKindItem>>
            projectDraftKinds(@PathVariable long pid) {
        return Response.builderSuccess(toolFcAssemblyService.getDraftKinds(
                pid, OperatorContext.currentOperatorId()));
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    @GetMapping("/{id}/detail")
    public Response<ToolDetail> detail(@PathVariable long id) {
        return Response.builderSuccess(toolService.getDetail(id));
    }

    
    @GetMapping("/{id}/versions")
    public Response<PageResult<ToolVersionInfo>> versions(@PathVariable long id,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(toolService.listVersions(id, page, size));
    }

    
    @GetMapping("/{id}/versions/{version}")
    public Response<ToolVersionDetail> versionDetail(@PathVariable long id,
                                                     @PathVariable int version) {
        return Response.builderSuccess(toolService.getVersionSnapshot(id, version));
    }

    
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id,
                                           @RequestParam(required = false) Integer version) {
        ToolDownloadFile file = toolService.download(OperatorContext.currentOperatorId(), id, version);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(file.content());
    }

    
    @PostMapping("/{id}/rollback")
    public Response<ToolInfo> rollback(@PathVariable long id, @RequestBody ToolRollbackInput input) {
        return Response.builderSuccess(
                toolService.rollback(OperatorContext.currentOperatorId(), id, input));
    }
}
