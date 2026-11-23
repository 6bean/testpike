package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;

import java.util.List;


public interface ApiCollectionService {

    
    ApiCollectionInfo create(Long operatorId, CollectionSaveInput input);

    
    default ApiCollectionInfo rename(Long operatorId, long collectionId, String newName, String tags) {
        return rename(operatorId, collectionId, newName, tags, null, null);
    }

    /**
 * Full save face : name/tags/headersJson/authJson, each null = keep stored
 * value. Collection defaults are shape-gated on write (array headers, auth via
 * AuthFormContract single source) so a bad block never reaches the merge point.
 */
    ApiCollectionInfo rename(Long operatorId, long collectionId, String newName, String tags,
                             String headersJson, String authJson);

    
    void delete(Long operatorId, long collectionId);

    
    /** Legacy single-project tree; delegates to the lens-aware form without a lens. */
    default List<ApiCollectionInfo> list(long projectId) {
        return list(projectId, 0L, null);
    }

    /**
     * Collection forest with an optional program lens.
     *
     * @param programId  project-set anchor; &lt;= 0 keeps the legacy single-project path
     *                   untouched (the lens arguments are then ignored).
     * @param operatorId caller used by ProgramLensScope to intersect the lens with the
     *                   visible domain (null = trusted internal call, no intersection).
     */
    List<ApiCollectionInfo> list(long projectId, long programId, Long operatorId);

    
    ApiCollectionInfo get(long collectionId);
}
