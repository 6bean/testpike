package com.sixbean.testpike.core.asset.api.dto;

import java.time.Instant;

/**
 * One version row of a shared asset chain with the reference pins attached to
 * exactly that version . The list
 * column carries the chain-level counter; the detail panel needs the per-version
 * distribution so consumers can see which versions the pool is actually pinning.
 * Resolved inside one grouped SELECT, never by probing each version row.
 *
 * @param assetRowId tp_asset version row id (the row every counted pin points at)
 * @param assetVersion chain-local version number of this row
 * @param status chain status of the row: 1 published, 2 withdrawn
 * @param head true for the newest version row of the chain
 * @param pinnedCount number of reference pins attached to this concrete version row
 * @param updatedAt when this version row was published
 */
public record AssetVersionTimelineInfo(
        long assetRowId,
        int assetVersion,
        int status,
        boolean head,
        long pinnedCount,
        Instant updatedAt) {
}
