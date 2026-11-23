package com.sixbean.testpike.admin.asset.infrastructure.remote.client;

import com.sixbean.testpike.admin.asset.api.dto.AssetPinForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetPublishForm;
import com.sixbean.testpike.admin.asset.api.dto.AssetReferenceView;
import com.sixbean.testpike.admin.asset.api.dto.AssetVersionTimelineView;
import com.sixbean.testpike.admin.asset.api.dto.SharedAssetView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import java.util.List;

/**
 * Shared-asset pool core query/write client (admin bridge over
 * /internal/shared-assets, tail).
 * <p>The owner fence, version-chain resolution, head/stale math and pin
 * upsert are core single-source; the bridge forwards parameters and mirrors
 * rows verbatim (ApiInventory bridge precedent, R40).
 */
public interface CoreSharedAssetClient {

    /** Publish or republish one asset version; 403 text names the owner project. */
    SharedAssetView publish(long callerProjectId, AssetPublishForm form);

    /** Read one version row by id (superseded rows stay resolvable for pins). */
    SharedAssetView get(long id);

    /** Globally discoverable published heads, optional kind filter and sort (whitelist in core). */
    PageView<SharedAssetView> list(String kind, int page, int size, String sortBy, String sortDir);

    /** Withdraw the whole (project, kind, name) chain, owner project only. */
    void withdraw(long callerProjectId, long assetId, boolean force);

    /** Register or move the host pin to a concrete version row (upsert). */
    AssetReferenceView pin(long callerProjectId, AssetPinForm form);

    /** Owner direction "who uses me": every pin across all versions of one chain. */
    PageView<AssetReferenceView> referencesToChain(long chainProjectId, String kind,
                                                   String name, int page, int size);

    /** Consumer direction "what I use": every pin held by one project. */
    PageView<AssetReferenceView> referencesByConsumer(long consumerProjectId, int page, int size);

    /**
     * Version timeline of one chain: per-version pin counts, newest first.
     * The grouped-SELECT pin math stays core single-source (R40).
     */
    List<AssetVersionTimelineView> chainVersions(long chainProjectId, String kind, String name);
}
