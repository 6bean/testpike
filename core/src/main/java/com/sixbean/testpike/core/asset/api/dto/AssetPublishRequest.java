package com.sixbean.testpike.core.asset.api.dto;

/**
 * Publish request for the shared asset chain .
 *
 * @param projectId owner project — the publishing project; writes from any other
 * project are rejected by the server-side owner fence
 * @param kind one of case_fragment | action_component | login_recipe | gen_function
 * (validated before the row is written, mirroring the DB CHECK)
 * @param name asset name, unique inside (project, kind) across the version chain
 * @param payloadJson content snapshot of the publisher's entity, per-kind semantics;
 * credential material must appear as {{CRED:key}} names only
 */
public record AssetPublishRequest(
        long projectId,
        String kind,
        String name,
        String payloadJson
) {
}
