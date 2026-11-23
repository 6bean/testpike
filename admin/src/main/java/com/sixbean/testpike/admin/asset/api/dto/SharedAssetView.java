package com.sixbean.testpike.admin.asset.api.dto;

/**
 * Admin mirror of the core shared-asset row (core SharedAssetInfo, field order
 * kept identical to prevent envelope drift). head/stale judgments and the
 * version chain resolution are core single-source; this view is rendered
 * verbatim by the pool pages and never recomputed here (R40).
 *
 * @param id              version row id (pins point at this row, not the chain)
 * @param projectId       owner (publishing) project id
 * @param ownerProjectName owner project display name, core-resolved
 * @param kind            asset family token (case_fragment|action_component|login_recipe|gen_function)
 * @param name            asset name within (project, kind) namespace
 * @param assetVersion    chain version (1-based, same-name republish increments)
 * @param prevAssetId     previous version row id, null on the first version
 * @param status          1 = published, 2 = withdrawn (chain level)
 * @param payloadJson     content snapshot of the publishing project's entity
 * @param head            true when this row is the current chain head
 * @param refCount        chain-level pin counter resolved in core's single list SELECT
 *                        (counts pins on any version row; 0 for unpinned chains)
 */
public record SharedAssetView(
        long id,
        long projectId,
        String ownerProjectName,
        String kind,
        String name,
        int assetVersion,
        Long prevAssetId,
        int status,
        String payloadJson,
        boolean head,
        int refCount
) {
}
