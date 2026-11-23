package com.sixbean.testpike.core.asset.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.asset.api.dto.AssetPublishRequest;
import com.sixbean.testpike.core.asset.api.dto.AssetReferenceInfo;
import com.sixbean.testpike.core.asset.api.dto.AssetReferencePinRequest;
import com.sixbean.testpike.core.asset.api.dto.AssetVersionTimelineInfo;
import com.sixbean.testpike.core.asset.api.dto.SharedAssetInfo;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetReferenceService;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shared asset internal endpoints .
 * <p>Permission narrowing happens on the admin side; the owner fence itself is enforced
 * server-side once inside {@link SharedAssetService} — this controller never mirrors it
 * (R40). The acting user travels in the X-Operator-Id header like every other internal
 * surface; callerProjectId is explicit so a forged body projectId cannot write into
 * another project's chain.
 */
@RestController
@RequestMapping("/internal/shared-assets")
public class SharedAssetInternalController {

    private final SharedAssetService assetService;
    private final SharedAssetReferenceService referenceService;

    public SharedAssetInternalController(SharedAssetService assetService,
                                         SharedAssetReferenceService referenceService) {
        this.assetService = assetService;
        this.referenceService = referenceService;
    }

    /** Publish (or re-publish) an asset version; validation and fence messages are single-sourced in the service. */
    @PostMapping
    public Response<SharedAssetInfo> publish(@RequestParam long callerProjectId,
                                             @RequestBody AssetPublishRequest request) {
        return Response.builderSuccess(assetService.publish(
                OperatorContext.currentOperatorId(), callerProjectId, request));
    }

    /** Read one version row by id (superseded/withdrawn rows stay resolvable for pinned references). */
    @GetMapping("/{id}")
    public Response<SharedAssetInfo> get(@PathVariable long id) {
        return Response.builderSuccess(assetService.get(id));
    }

    /** Globally discoverable published heads, optional kind filter, whitelisted sort. */
    @GetMapping
    public Response<PageResult<SharedAssetInfo>> list(@RequestParam(required = false) String kind,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String sortBy,
                                                      @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(assetService.listDiscoverable(kind, page, size, sortBy, sortDir));
    }

    /** Withdraw the whole (project, kind, name) chain; owner project only, 403 names the owner. */
    @PostMapping("/{id}/withdraw")
    public Response<Void> withdraw(@RequestParam long callerProjectId, @PathVariable long id,
                                   @RequestParam(defaultValue = "false") boolean force) {
        assetService.withdraw(OperatorContext.currentOperatorId(), callerProjectId, id, force);
        return Response.builderSuccess(null);
    }

    /** Register or move one host pin to a concrete asset version row (upsert, single row per host). */
    @PostMapping("/references")
    public Response<AssetReferenceInfo> pin(@RequestParam long callerProjectId,
                                            @RequestBody AssetReferencePinRequest request) {
        return Response.builderSuccess(referenceService.pin(callerProjectId,
                request.assetRowId(), request.referrerKind(), request.referrerId()));
    }

    /** Owner direction "who uses me": every pin on any version of the given asset chain. */
    @GetMapping("/references/to-chain")
    public Response<PageResult<AssetReferenceInfo>> listReferencesToChain(
            @RequestParam long chainProjectId,
            @RequestParam String kind,
            @RequestParam String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(
                referenceService.listReferencesToChain(chainProjectId, kind, name, page, size));
    }

    /** Consumer direction "what I use": every pin held by the given project. */
    @GetMapping("/references/by-consumer")
    public Response<PageResult<AssetReferenceInfo>> listReferencesByConsumer(
            @RequestParam long consumerProjectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(
                referenceService.listReferencesByConsumer(consumerProjectId, page, size));
    }

    /** Version timeline of one chain: per-version pin counts, newest first, single SELECT. */
    @GetMapping("/references/chain-versions")
    public Response<List<AssetVersionTimelineInfo>> listChainVersions(
            @RequestParam long chainProjectId,
            @RequestParam String kind,
            @RequestParam String name) {
        return Response.builderSuccess(
                referenceService.listChainVersionTimeline(chainProjectId, kind, name));
    }
}
