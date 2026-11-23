package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * API test collection update request (hierarchy never changes; since the
 * collection-level default headers/auth ride along).
 * <p>Patch semantics: a null field keeps the stored value unchanged. Shape
 * legality (array headers, auth vocabulary) is judged solely at the core save
 * gate — admin composes and forwards, it never re-validates.
 *
 * @param name new collection name (409 when it collides in the same level; blank = keep)
 * @param tags comma-separated tag text (null = keep)
 * @param headersJson collection default headers as JSON text (null = keep)
 * @param authJson collection default auth block as JSON text (null = keep)
 */
public record CollectionUpdateRequest(String name, String tags,
                                      String headersJson, String authJson) {

    /** Legacy two-argument form: rename/tag only, defaults untouched. */
    public CollectionUpdateRequest(String name, String tags) {
        this(name, tags, null, null);
    }
}
