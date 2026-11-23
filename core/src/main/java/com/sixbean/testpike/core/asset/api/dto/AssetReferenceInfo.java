package com.sixbean.testpike.core.asset.api.dto;

import java.time.Instant;

/**
 * One reference pin of the shared asset pool , read model for both
 * directions of the bidirectional query: the owner side asks "who uses me" and sees the
 * consumer project, host locator and pinned version; the consumer side asks "what I use"
 * and sees the asset identity with owner attribution.
 *
 * @param id reference row id inside tp_asset_reference
 * @param assetRowId pinned tp_asset version row id (a concrete version, never a chain)
 * @param assetProjectId owner (publishing) project of the pinned asset chain
 * @param ownerProjectName owner project name for attribution in the reference panel
 * @param kind asset kind literal (case_fragment | action_component | login_recipe | gen_function)
 * @param name asset chain name
 * @param pinnedVersion asset_version of the pinned row
 * @param assetStatus chain status of the pinned row: 1 published, 2 withdrawn
 * @param headVersion newest asset_version currently present on the chain
 * @param stale pinned version is older than the chain head (upstream re-published)
 * @param referrerProjectId consumer project owning the host entity
 * @param referrerProjectName consumer project name
 * @param referrerKind host entity type literal (e.g. case_step)
 * @param referrerId host entity row id inside the consumer project
 * @param createdAt when the pin was registered
 */
public record AssetReferenceInfo(
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
