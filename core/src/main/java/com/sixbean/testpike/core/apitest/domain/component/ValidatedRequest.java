package com.sixbean.testpike.core.apitest.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * API request save-chain product (normalized column values, immutable record).
 * <p>Lives in domain/component (value object, no inner classes). toParams is the single
 * source of SQL bindings shared by the create and update chains (no duplicated logic).
 * contractEndpointKey carries the normalized contract match key written by the save
 * chain so inventory joins and stale-badge closure hit it by index, not per-row recompute.
 */
public record ValidatedRequest(String name, String method, String urlPath,
                               String headersJson, String bodyText, String authJson,
                               String assertionsJson, String contractEndpointKey,
                               String preconditionJson, String loopJson, String protocol) {

    /** Assemble named SQL params (::jsonb casts live in the SQL text of each chain). */
    public MapSqlParameterSource toParams(long collectionId, Long operatorId) {
        return new MapSqlParameterSource()
                .addValue("collectionId", collectionId)
                .addValue("name", name)
                .addValue("method", method)
                .addValue("urlPath", urlPath)
                .addValue("headersJson", headersJson)
                .addValue("bodyText", bodyText)
                .addValue("authJson", authJson)
                .addValue("assertionsJson", assertionsJson)
                .addValue("contractEndpointKey", contractEndpointKey)
                .addValue("preconditionJson", preconditionJson)
                .addValue("loopJson", loopJson)
                .addValue("protocol", protocol)
                .addValue("operatorId", operatorId);
    }
}
