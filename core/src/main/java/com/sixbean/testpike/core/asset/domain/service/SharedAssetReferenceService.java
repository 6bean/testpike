package com.sixbean.testpike.core.asset.domain.service;

import com.sixbean.testpike.core.asset.api.dto.AssetReferenceInfo;
import com.sixbean.testpike.core.asset.api.dto.AssetVersionTimelineInfo;
import com.sixbean.testpike.core.common.clientobject.PageResult;

import java.util.List;

/**
 * Reference pins of the shared asset pool over tp_asset_reference (V359).
 * A host entity (referrer_kind + referrer_id) holds exactly one pin to a concrete asset
 * version row; re-publishing upstream never moves the pin automatically — the read model
 * surfaces the staleness marker and the consumer re-pins explicitly (upsert keeps a
 * single row per host). Withdrawn chains stay queryable here so the withdrawal flow
 * can name every active consumer from this same source (fail-closed).
 */
public interface SharedAssetReferenceService {

    /**
     * Register or move the pin of one host to a concrete asset version row.
     * The acting project is recorded as the consumer; validation runs before any SQL
     * write. Unknown asset rows fail 404, blank host locators fail 400.
     *
     * @param callerProjectId consumer project the acting request belongs to
     * @param assetRowId      tp_asset version row id to pin
     * @param referrerKind    host entity type literal (non-blank)
     * @param referrerId      host entity row id inside the consumer project (positive)
     * @return the stored pin with owner attribution and staleness marker
     */
    AssetReferenceInfo pin(long callerProjectId, long assetRowId,
                           String referrerKind, long referrerId);

    /**
     * Owner direction "who uses me": every pin on any version of the given asset chain,
     * newest first, resolved in a single SQL join (no N+1 per version row).
     */
    PageResult<AssetReferenceInfo> listReferencesToChain(long chainProjectId, String kind,
                                                         String name, int page, int size);

    /**
     * Consumer direction "what I use": every pin held by the given project,
     * newest first, with asset identity and staleness marker in one SQL join.
     */
    PageResult<AssetReferenceInfo> listReferencesByConsumer(long consumerProjectId,
                                                            int page, int size);

    /**
     * Version timeline of one asset chain: every version row of the
     * (chainProjectId, kind, name) chain with the number of pins attached to
     * exactly that row, newest first. Resolved in a single grouped SELECT from
     * the shared AssetChainQuery source (no per-version probe, no N+1). Blank
     * kind/name fail 400 like the other chain locators; an unknown chain reads
     * as an empty timeline rather than an error.
     */
    List<AssetVersionTimelineInfo> listChainVersionTimeline(long chainProjectId, String kind,
                                                            String name);
}
