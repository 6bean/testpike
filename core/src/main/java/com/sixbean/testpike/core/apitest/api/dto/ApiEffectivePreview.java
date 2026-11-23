package com.sixbean.testpike.core.apitest.api.dto;

import java.util.List;

/**
 * Effective-defaults preview of one API request : what the
 * dispatch envelope will actually carry after collection inheritance, plus the
 * per-piece provenance the editor shows as source chips.
 * <p>Secret posture: auth slots carry credential references ({@code @cred/key},
 * {@code {{CRED:key}}}) exactly as stored — the vault real value is injected
 * only at dispatch, so the preview needs no masking and never echoes secrets.
 *
 * @param requestId the request this preview belongs to
 * @param collectionId owning collection (id only — access checks run on the admin face)
 * @param collectionName owning collection name shown next to inherited chips
 * @param headers merged header rows with per-key source
 * @param headersJson merged headers in envelope object form (same bytes the
 * envelope carries; kept for the editor's raw view)
 * @param authJson effective auth block as resolved (empty object when none)
 * @param authSource four-valued verdict: {@code request} (own block),
 * {@code collection} (inherited), {@code none-explicit}
 * ({"type":"none"} opt-out, never falls back),
 * {@code none} (nothing declared anywhere)
 */
public record ApiEffectivePreview(long requestId, long collectionId, String collectionName,
                                  List<ApiEffectiveHeader> headers, String headersJson,
                                  String authJson, String authSource) {
}
