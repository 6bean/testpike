package com.sixbean.testpike.admin.asset.api.dto;

import java.time.Instant;

/**
 * Admin mirror of one asset pin row (core AssetReferenceInfo, field order kept
 * identical). pinnedVersion vs headVersion and the stale flag are computed by
 * the core read model in one join (bidirectional query single source, zero
 * N+1); the bridge only forwards rows for rendering.
 *
 * @param id                pin row id
 * @param assetRowId        the concrete tp_asset version row this pin points at
 * @param assetProjectId    owner (publishing) project id of the pinned chain
 * @param ownerProjectName  owner project display name
 * @param kind              asset family token
 * @param name              asset name
 * @param pinnedVersion     chain version captured at pin time
 * @param assetStatus       pinned row status (1 published / 2 withdrawn)
 * @param headVersion       current head version of the chain
 * @param stale             pinnedVersion behind headVersion (upstream republished)
 * @param referrerProjectId consuming project holding the pin
 * @param referrerProjectName consuming project display name
 * @param referrerKind      host entity family (e.g. case_step)
 * @param referrerId        host entity id inside the consuming project
 * @param createdAt         pin registration instant
 */
public record AssetReferenceView(
        long id,
        long assetRowId,
        long assetProjectId,
        String ownerProjectName,
        String kind,
        String name,
        int pinnedVersion,
        int assetStatus,
        int headVersion,
        boolean stale,
        long referrerProjectId,
        String referrerProjectName,
        String referrerKind,
        long referrerId,
        Instant createdAt) {
}
