package com.sixbean.testpike.core.apitest.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.apitest.api.dto.ApiEffectivePreview;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.AuthPresetInfo;
import com.sixbean.testpike.core.apitest.api.dto.PromotionResultView;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.component.AuthPresetCatalog;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
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

/**
 * The API request defines an internal interface (core service layer, called by admin over HTTP).
 * <p>Permissions (project members) are validated on the admin side, and the operator is passed through from X-Operator-Id to complete the audit loop.
 */
@RestController
@RequestMapping("/internal/apitest/requests")
public class ApiRequestInternalController {

    private final ApiRequestService apiRequestService;
    private final PromotionService promotionService;
    private final AuthPresetCatalog authPresetCatalog;

    public ApiRequestInternalController(ApiRequestService apiRequestService,
                                        PromotionService promotionService,
                                        AuthPresetCatalog authPresetCatalog) {
        this.apiRequestService = apiRequestService;
        this.promotionService = promotionService;
        this.authPresetCatalog = authPresetCatalog;
    }

    /** The create request returns 404 if the collection does not exist, rejects SSRF absolute URLs at save time, and statically validates assertion groups. */
    @PostMapping
    public Response<ApiRequestInfo> create(@RequestBody RequestSaveInput input) {
        return Response.builderSuccess(
                apiRequestService.create(OperatorContext.currentOperatorId(), input));
    }

    /** The full update request uses the same validation as create and returns 404 if not found. */
    @PutMapping("/{id}")
    public Response<ApiRequestInfo> update(@PathVariable long id,
                                           @RequestBody RequestSaveInput input) {
        return Response.builderSuccess(
                apiRequestService.update(OperatorContext.currentOperatorId(), id, input));
    }

    /** The delete request returns 409 if a run already exists and preserves traceability with a human-readable response. */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        apiRequestService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }

    /** Collection members in the user-maintained order (sort_order asc). */
    @GetMapping
    public Response<List<ApiRequestInfo>> list(@RequestParam long collectionId) {
        return Response.builderSuccess(apiRequestService.list(collectionId));
    }

    /**
 * Persist the user-maintained member order of a collection .
 * The body is the full id list in the desired front-to-back order; a stale or
 * foreign set is rejected 400 by the core service (single validation point).
 */
    @PutMapping("/{collectionId}/order")
    public Response<Void> reorder(@PathVariable long collectionId,
                                  @RequestBody List<Long> orderedRequestIds) {
        apiRequestService.reorder(OperatorContext.currentOperatorId(), collectionId, orderedRequestIds);
        return Response.builderSuccess(null);
    }

    /** The request details endpoint returns 404 if the request does not exist. */
    @GetMapping("/{id}")
    public Response<ApiRequestInfo> get(@PathVariable long id) {
        return Response.builderSuccess(apiRequestService.get(id));
    }

    /**
 * Effective-defaults preview : merged headers + resolved
 * auth with per-piece provenance — the same verdict the dispatch envelope
 * uses, served read-only for the editor. Auth slots carry credential
 * references as stored, never vault values (zero secret surface).
 */
    @GetMapping("/{id}/effective")
    public Response<ApiEffectivePreview> effective(@PathVariable long id) {
        return Response.builderSuccess(apiRequestService.effectivePreview(id));
    }

    /**
 * Batch effective-defaults preview for a whole collection :
 * the interface-set import preview reads every member's merged headers/auth
 * in ONE round trip (the per-request face would cost 500 calls at worst
 * scale). Same resolver verdict row-for-row; unknown collection 404 passes
 * through. Read-only bridge — access guard runs on the admin face.
 */
    @GetMapping("/effective-batch")
    public Response<List<ApiEffectivePreview>> effectiveBatch(@RequestParam long collectionId) {
        return Response.builderSuccess(apiRequestService.effectivePreviewBatch(collectionId));
    }

    /**
     * Signature auth preset catalogue (W1c): config rows under
     * apitest.auth.preset., served as editor dropdown data. Read-only and
     * project-independent (templates carry references, never secret values).
     */
    @GetMapping("/auth-presets")
    public Response<List<AuthPresetInfo>> authPresets() {
        return Response.builderSuccess(authPresetCatalog.presets());
    }

    /**
 * Promote one tuned sandbox request into a regression case .
 * All validation lives in PromotionService — 404 unknown request, 400
 * unmappable assertions or an auth type the runner cannot honour, 40904
 * repeat promotion echoing the existing case number. Operator comes from
 * the X-Operator-Id header (audit closure, no second mechanism).
 */
    @PostMapping("/{id}/promote")
    public Response<PromotionResultView> promote(@PathVariable long id) {
        return Response.builderSuccess(
                promotionService.promote(OperatorContext.currentOperatorId(), id));
    }
}
