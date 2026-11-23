package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.AuthPresetDto;
import com.sixbean.testpike.admin.apitest.api.dto.EffectivePreviewDto;
import com.sixbean.testpike.admin.apitest.api.dto.PromotionResultDto;
import com.sixbean.testpike.admin.apitest.api.dto.RequestSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RequestView;

import java.util.List;


public interface CoreApiRequestClient {

    
    RequestView createRequest(RequestSaveRequest request);

    
    RequestView updateRequest(long id, RequestSaveRequest request);

    
    void deleteRequest(long id);

    /** Collection members in the user-maintained order (sort_order asc). */
    List<RequestView> listRequests(long collectionId);

    /**
     * Persist the user-maintained member order of a collection (full id list,
     * front to back). 400 human messages (stale set / foreign id) pass through
     * from the core single validation point.
     */
    void reorderRequests(long collectionId, List<Long> orderedRequestIds);

    
    RequestView getRequest(long id);

    /**
     * Effective-defaults preview (core ApiRequestService single source): merged
     * headers + resolved auth with provenance; references as stored, never
     * vault values. 404 passes through.
     */
    EffectivePreviewDto effectivePreview(long id);

    /**
 * Batch effective-defaults preview for a whole collection :
 * one round trip for every member's merged headers/auth, row-for-row the
 * same verdict as {@link #effectivePreview}. Powers the interface-set
 * import preview at worst scale (500 requests, no per-row calls).
 */
    List<EffectivePreviewDto> effectivePreviewBatch(long collectionId);

    /**
     * Promote one tuned sandbox request into a regression case (core
     * PromotionService single source; 404/400/40904 pass through verbatim —
     * the 40904 message names the already-promoted case number).
     */
    PromotionResultDto promoteRequest(long id);

    /** Signature auth preset catalogue (core AuthPresetCatalog single source). */
    List<AuthPresetDto> listAuthPresets();
}
