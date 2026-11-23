package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/**
 *
 * @param requestCount requests held directly by this collection (mirrors core;
 * drives the interface-count badge on the workbench rail)
 * @param headersJson collection default headers as normalized jsonb text (;
 * '[]' = member requests inherit nothing)
 * @param authJson collection default auth block as normalized jsonb text
 *
 */
public record ApiCollectionView(long id, long projectId, long parentId, String name,
                                String tags, long requestCount,
                                String headersJson, String authJson,
                                List<ApiCollectionView> children) {
}
