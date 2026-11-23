package com.sixbean.testpike.core.delreq.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestCreateInput;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestInfo;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestRejectInput;
import com.sixbean.testpike.core.delreq.domain.service.DeleteRequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/delete-requests")
public class DeleteRequestInternalController {

    private final DeleteRequestService deleteRequestService;

    public DeleteRequestInternalController(DeleteRequestService deleteRequestService) {
        this.deleteRequestService = deleteRequestService;
    }

    
    @PostMapping
    public Response<DeleteRequestInfo> create(@RequestBody DeleteRequestCreateInput input) {
        return Response.builderSuccess(
                deleteRequestService.create(OperatorContext.currentOperatorId(), input));
    }

    /**
 * Project-scoped list (H1 isolation; 02 GET /delete-requests). The
 * optional sortBy/sortDir pair is forwarded to the core sort whitelist
 * — absent keeps the historical id DESC order.
 */
    @GetMapping
    public Response<PageResult<DeleteRequestInfo>> list(
            @RequestParam long projectId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                deleteRequestService.list(projectId, status, page, size, sortBy, sortDir));
    }

    
    @GetMapping("/{id}")
    public Response<DeleteRequestInfo> get(@PathVariable long id) {
        return Response.builderSuccess(deleteRequestService.get(id));
    }

    
    @PostMapping("/{id}/approve")
    public Response<DeleteRequestInfo> approve(@PathVariable long id) {
        return Response.builderSuccess(
                deleteRequestService.approve(OperatorContext.currentOperatorId(), id));
    }

    
    @PostMapping("/{id}/reject")
    public Response<DeleteRequestInfo> reject(@PathVariable long id,
                                              @RequestBody DeleteRequestRejectInput input) {
        return Response.builderSuccess(
                deleteRequestService.reject(OperatorContext.currentOperatorId(), id, input));
    }
}
