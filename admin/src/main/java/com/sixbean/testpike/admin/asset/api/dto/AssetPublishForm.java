package com.sixbean.testpike.admin.asset.api.dto;

/**
 * Publish payload of the shared asset pool door (mirror of core
 * AssetPublishRequest field order). The admin bridge passes projectId
 * positionally as callerProjectId as well, so the core owner fence
 * (caller == chain owner, 403 names the owner project) is always evaluated
 * against the acting project; a body targeting another project still fails at
 * core even if the admin-side H guard were bypassed.
 *
 * @param projectId acting (owner) project id, from the header project segment
 * @param kind asset family token
 * @param name asset name within (project, kind) namespace
 * @param payloadJson content snapshot taken from the owner project's entity
 */
public record AssetPublishForm(
        long projectId,
        String kind,
        String name,
        String payloadJson
) {
}
