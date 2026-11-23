package com.sixbean.testpike.core.asset.api.dto;

/**
 * Request body registering or moving one reference pin .
 * The consumer project comes from the callerProjectId request parameter, never from
 * this body, so a forged payload cannot write a pin into another project's ledger.
 *
 * @param assetRowId tp_asset version row id to pin (a concrete version, never a chain)
 * @param referrerKind host entity type literal (e.g. case_step); non-blank
 * @param referrerId host entity row id inside the consumer project; positive
 */
public record AssetReferencePinRequest(
        long assetRowId,
        String referrerKind,
        long referrerId) {
}
