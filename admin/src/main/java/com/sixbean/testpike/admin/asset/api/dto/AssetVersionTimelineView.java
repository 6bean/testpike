package com.sixbean.testpike.admin.asset.api.dto;

import java.time.Instant;

/**
 * Mirror row of the core version timeline for one shared-asset chain
 * .
 * The grouped-SELECT pin math and the head marker are core single-source;
 * the bridge forwards rows verbatim.
 *
 * @param assetRowId tp_asset version row id the counted pins point at
 * @param assetVersion chain-local version number of this row
 * @param status chain status of the row: 1 published, 2 withdrawn
 * @param head true for the newest version row of the chain
 * @param pinnedCount number of reference pins attached to this version row
 * @param updatedAt when this version row was published
 */
public record AssetVersionTimelineView(
        long assetRowId,
        int assetVersion,
        int status,
        boolean head,
        long pinnedCount,
        Instant updatedAt) {
}
