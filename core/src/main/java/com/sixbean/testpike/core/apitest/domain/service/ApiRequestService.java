package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.ApiEffectivePreview;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;

import java.util.List;
import java.util.Set;


public interface ApiRequestService {

    
    ApiRequestInfo create(Long operatorId, RequestSaveInput input);

    
    ApiRequestInfo update(Long operatorId, long requestId, RequestSaveInput input);

    
    void delete(Long operatorId, long requestId);

    /**
 * Explicit user-maintained order of the collection's members :
 * the launch preview and whole-set execution freeze definitions in this order.
 * The submitted id list must cover exactly the current members — a stale set
 * (concurrent add/remove) or a foreign id is rejected 400 with a human message
 * instead of silently dropping rows.
 */
    void reorder(Long operatorId, long collectionId, List<Long> orderedRequestIds);

    /** Collection members in the user-maintained order (sort_order asc, id as stable tie-break). */
    List<ApiRequestInfo> list(long collectionId);

    
    ApiRequestInfo get(long requestId);

    /**
 * Effective-defaults preview of one request : the merged
 * headers and resolved auth the dispatch envelope will carry, each piece
 * labelled with its provenance (request / collection / none-explicit /
 * none). Same verdict as the envelope through one resolver — the editor
 * chip face must never grow a second interpretation (red line 6).
 * Auth slots stay as stored references ({@code @cred/key} / {{CRED:key}}),
 * never vault-resolved (the preview carries zero secret values).
 */
    ApiEffectivePreview effectivePreview(long requestId);

    /**
 * Batch effective-defaults preview of every member of one collection
 * : the whole interface-set import into a perf scenario needs
 * per-request merged headers/auth in a single round trip — the per-request
 * face would cost 500 calls at worst scale. Rows follow the user-maintained
 * member order and are row-for-row identical to {@link #effectivePreview}
 * (one resolver verdict, zero second interpretation). Unknown collection →
 * 404 human message; empty collection → empty list.
 */
    List<ApiEffectivePreview> effectivePreviewBatch(long collectionId);

    
    int markContractStale(long projectId, Set<String> endpointKeys);

    /**
 * Mock twin badge : among the same diff-hit keys, flag the
 * project's requests that also have a contract-derived (source=openapi)
 * stub in one of the project's mock spaces. Scope is always a subset of
 * markContractStale (same SpecDiff keys, zero second judgment source);
 * cleared on edit together with the contract badge.
 *
 * @return rows actually flagged
 */
    int markMockStale(long projectId, Set<String> endpointKeys);
}
