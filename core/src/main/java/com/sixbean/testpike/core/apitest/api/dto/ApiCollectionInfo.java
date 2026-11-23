package com.sixbean.testpike.core.apitest.api.dto;

import java.util.List;

/**
 * API test collection item (tree lists attach sub-collections under children;
 * single-object reads return an empty children list).
 *
 * @param id collection id
 * @param projectId owning project
 * @param parentId parent collection id (0 = root)
 * @param name collection name
 * @param tags comma-separated tag text
 * @param requestCount number of API requests stored directly in this collection
 * (children carry their own count; no rollup — both levels
 * render side by side, a sum would double-count)
 * @param headersJson collection default headers as normalized jsonb text
 *
 * @param authJson collection default auth block as normalized jsonb text
 * (; '{}' = nothing inherited; vocabulary single
 * source AuthFormContract)
 * @param children sub-collections (only the tree list fills these, max one level)
 */
public record ApiCollectionInfo(long id, long projectId, long parentId, String name,
                                String tags, long requestCount,
                                String headersJson, String authJson,
                                List<ApiCollectionInfo> children) {
}
