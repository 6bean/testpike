package com.sixbean.testpike.core.apiaccess.api.dto;

import java.util.List;

/**
 * Catalog row as the dialog sees it: what the endpoint is, whether the door may serve it, and why
 * not when it may not.
 *
 * @param key            endpoint handle to pass back to the read door
 * @param httpMethod     declared method
 * @param pathPattern    path pattern with placeholders
 * @param params         addressable parameters (non-addressable ones are hidden: they cannot be filled)
 * @param writeClass     true for change-class methods (listed so callers can see what the door refuses)
 * @param projectAnchored true when the endpoint carries a project id parameter
 * @param reachable      verdict of the policy without arguments
 * @param reason         blocking reason when unreachable, null otherwise
 * @param description    registered description, falling back to one derived from the path
 * @param bodyType       declared payload type of a change entry, null when it takes no payload
 * @param bodyFields     the payload's declared fields — the only keys a change may be composed
 *                       from, so the dialog has to see them before it can propose anything
 */
public record ApiAccessEndpointView(
        String key,
        String httpMethod,
        String pathPattern,
        List<ApiEndpointParam> params,
        boolean writeClass,
        boolean projectAnchored,
        boolean reachable,
        String reason,
        String description,
        String bodyType,
        List<String> bodyFields) {
}
