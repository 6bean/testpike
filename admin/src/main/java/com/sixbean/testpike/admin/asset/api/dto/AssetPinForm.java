package com.sixbean.testpike.admin.asset.api.dto;

/**
 * Pin registration form of the consumer door (core AssetReferencePinRequest
 * plus the acting consumer projectId from the header project segment). Upsert
 * semantics (one row per host entity, move = repoint to another version row)
 * are core single-source; the bridge only forwards.
 *
 * @param projectId consuming project id (the referrer side)
 * @param assetRowId concrete tp_asset version row id to pin
 * @param referrerKind host entity family (e.g. case_step)
 * @param referrerId host entity id inside the consuming project
 */
public record AssetPinForm(
        long projectId,
        long assetRowId,
        String referrerKind,
        long referrerId
) {
}
