package com.sixbean.testpike.core.apitest.api.dto;

/**
 * API test collection save input (tp_apitest_collection).
 * <p>headersJson/authJson are the collection-level defaults stored in the
 * headers_json/auth_json columns: requests inside one collection usually share a
 * single auth block and a few default headers. The merge happens at the one
 * dispatch point (ApiTestEnvelopeAssembler): headers merge per key
 * case-insensitively with the request winning same-name entries, auth replaces as
 * a whole block with an explicit {"type":"none"} opt-out that never falls back.
 *
 * @param projectId owning project (H1 isolation dimension)
 * @param parentId parent collection id (0 = root; the tree is hard-limited to two levels)
 * @param name collection name (unique per project+level, max 100 chars)
 * @param tags comma-separated tag text (optional)
 * @param headersJson collection default headers as JSON text ([{name,value}], empty = nothing inherited)
 * @param authJson collection default auth block as JSON text ({type,...}, {} = nothing inherited;
 * vocabulary single source AuthFormContract, refused at the save gate when malformed)
 */
public record CollectionSaveInput(Long projectId, Long parentId, String name, String tags,
                                  String headersJson, String authJson) {

    /** Legacy four-argument form: no collection-level defaults. */
    public CollectionSaveInput(Long projectId, Long parentId, String name, String tags) {
        this(projectId, parentId, name, tags, null, null);
    }

    /** Three-argument shorthand (tags absent). */
    public CollectionSaveInput(long projectId, long parentId, String name) {
        this(projectId, parentId, name, null, null, null);
    }
}
