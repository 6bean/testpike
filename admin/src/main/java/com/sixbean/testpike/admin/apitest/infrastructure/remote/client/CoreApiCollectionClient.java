package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.ApiCollectionView;
import com.sixbean.testpike.admin.apitest.api.dto.CollectionSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.CollectionUpdateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.FlowPromotionResultDto;

import java.util.List;


public interface CoreApiCollectionClient {

    
    ApiCollectionView createCollection(CollectionSaveRequest request);

    
    ApiCollectionView renameCollection(long id, CollectionUpdateRequest request);

    
    void deleteCollection(long id);

    
    default List<ApiCollectionView> listCollections(long projectId) {
        return listCollections(projectId, 0L);
    }

    List<ApiCollectionView> listCollections(long projectId, long programId);

    
    ApiCollectionView getCollection(long id);

    /**
     * Promote the collection's whole linear request chain into one regression
     * case (core POST /{id}/promote-flow). Judgement is core-side only — the
     * bridge passes 400/404/409 semantics through verbatim.
     */
    FlowPromotionResultDto promoteFlow(long id);
}
