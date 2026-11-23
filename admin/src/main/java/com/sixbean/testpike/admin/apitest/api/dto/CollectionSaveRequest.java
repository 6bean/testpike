package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * API test collection save request (tree is hard-limited to two levels,
 * parentId=0 = root).
 * <p>headersJson/authJson are the collection-level defaults, passed straight
 * to the core save gate (shape + vocabulary verdicts stay single source there —
 * the admin layer never grows a second dialect).
 *
 * @param projectId owning project (H1 isolation dimension)
 * @param parentId parent collection id (0 = root)
 * @param name collection name (unique per project+level)
 * @param tags comma-separated tag text (optional)
 * @param headersJson collection default headers as JSON text ([{name,value}], optional)
 * @param authJson collection default auth block as JSON text ({type,...}, optional)
 */
public record CollectionSaveRequest(Long projectId, Long parentId, String name, String tags,
                                    String headersJson, String authJson) {

    /** Legacy four-argument form: no collection-level defaults. */
    public CollectionSaveRequest(Long projectId, Long parentId, String name, String tags) {
        this(projectId, parentId, name, tags, null, null);
    }
}
