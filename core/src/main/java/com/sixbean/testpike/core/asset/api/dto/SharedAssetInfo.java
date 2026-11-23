package com.sixbean.testpike.core.asset.api.dto;

/**
 * One version row of the shared asset publish chain .
 *
 * @param id row id; references pin this concrete id across upstream updates
 * @param projectId owner (publishing) project
 * @param ownerProjectName project name carried for human-readable attribution in lists
 * and in the 403 message of a rejected cross-project write
 * @param kind case_fragment | action_component | login_recipe | gen_function
 * @param name asset name inside the (project, kind) chain
 * @param assetVersion 1-based chain position, +1 on every re-publish
 * @param prevAssetId previous version row id, NULL for the first publish
 * @param status 1 = published, 2 = withdrawn (chain level)
 * @param payloadJson content snapshot stored at publish time
 * @param head true when this row is the newest version of its chain
 * @param refCount pins attached to ANY version row of this chain (chain-level
 * counter for the discoverable list; 0 for unpinned chains)
 */
public record SharedAssetInfo(
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
