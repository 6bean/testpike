package com.sixbean.testpike.core.apitest.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.FlowPromotionResultView;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.PromotionService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Internal interface for API test collections in the core service layer, called by admin over HTTP.
 * <p>Permissions (project members) are validated on the admin side, and the operator is passed through from X-Operator-Id to complete the audit loop.
 *
 */
@RestController
@RequestMapping("/internal/apitest/collections")
public class ApiCollectionInternalController {

    private final ApiCollectionService apiCollectionService;
    private final PromotionService promotionService;

    public ApiCollectionInternalController(ApiCollectionService apiCollectionService,
                                           PromotionService promotionService) {
        this.apiCollectionService = apiCollectionService;
        this.promotionService = promotionService;
    }

    /** Create a collection, returning 400 for creation at level 3 and 409 for duplicate names at the same level. */
    @PostMapping
    public Response<ApiCollectionInfo> create(@RequestBody CollectionSaveInput input) {
        return Response.builderSuccess(
                apiCollectionService.create(OperatorContext.currentOperatorId(), input));
    }

    /** Rename / retag / update collection default headers-auth (409 on same-level
     * name collision; an omitted field keeps its stored value). */
    @PutMapping("/{id}")
    public Response<ApiCollectionInfo> rename(@PathVariable long id, @RequestBody Map<String, String> body) {
        return Response.builderSuccess(apiCollectionService.rename(
                OperatorContext.currentOperatorId(), id,
                body.get("name"), body.get("tags"),
                body.get("headersJson"), body.get("authJson")));
    }

    /** Delete a collection, returning 409 with a human-readable message if it has subdirectories. */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        apiCollectionService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    /** Retrieve the collection forest — single project, or across a program lens when programId &gt; 0. */
    @GetMapping
    public Response<List<ApiCollectionInfo>> list(@RequestParam long projectId,
                                                  @RequestParam(defaultValue = "0") long programId) {
        return Response.builderSuccess(
                apiCollectionService.list(projectId, programId, OperatorContext.currentOperatorId()));
    }

    /** Get collection details, returning 404 if the collection does not exist. */
    @GetMapping("/{id}")
    public Response<ApiCollectionInfo> get(@PathVariable long id) {
        return Response.builderSuccess(apiCollectionService.get(id));
    }

    /**
 * Promote the collection's whole linear request chain into one regression
 * case . All judgement lives in PromotionService — 404
 * missing collection, 400 orchestration/ws members or an empty flow named
 * in plain words, 40904 an already-promoted member echoing its case.
 * Operator arrives via X-Operator-Id (audit closure, no second mechanism).
 */
    @PostMapping("/{id}/promote-flow")
    public Response<FlowPromotionResultView> promoteFlow(@PathVariable long id) {
        return Response.builderSuccess(
                promotionService.promoteFlow(OperatorContext.currentOperatorId(), id));
    }
}
