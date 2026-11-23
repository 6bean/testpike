package com.sixbean.testpike.core.apiaccess.api.dto;

import java.util.List;

/**
 * One cataloged endpoint of this application, as derived from the live request mappings.
 *
 * <p>The catalog is generated, never hand-written: a new controller endpoint shows up here
 * without anyone registering a tool for it, which is the whole point of the pass-through door.
 *
 * @param key             stable handle the caller must use ({@code HTTP-METHOD /path/pattern})
 * @param httpMethod      declared HTTP method (uppercase)
 * @param pathPattern     path pattern with {@code {placeholder}} segments intact
 * @param params          addressable and non-addressable parameters of the handler method
 * @param writeClass      true when the method is not a safe read (anything but GET/HEAD)
 * @param unsupportedReason non-null when the read door cannot serve the shape (request body,
 *                          or a parameter the caller cannot address)
 * @param bodyType          declared request-body type name, null when the method has no body
 * @param bodyFields        the body type's own declared fields: the only keys a change landing may
 *                          compose. Kept as a whitelist so a proposal cannot smuggle extra columns
 *                          into an internal door (mass assignment).
 */
public record ApiEndpointSpec(
        String key,
        String httpMethod,
        String pathPattern,
        List<ApiEndpointParam> params,
        boolean writeClass,
        String unsupportedReason,
        String bodyType,
        List<String> bodyFields) {

    /** Read-door view of the same entry (no body involved). */
    public ApiEndpointSpec withoutBody() {
        return new ApiEndpointSpec(key, httpMethod, pathPattern, params, writeClass,
                unsupportedReason, null, List.of());
    }

    /** Parameter names that anchor a call to one project (first match wins). */
    private static final List<String> PROJECT_ANCHOR_NAMES = List.of("projectId", "projectIds", "pid");

    /**
     * Parameter names that anchor a call to the acting person (first match wins). An endpoint
     * carrying one of these is not unscoped: it serves exactly one operator's rows, so the door
     * can judge its reachability from the trusted identity and re-stamp the value server-side
     * (same posture as the project anchor's session re-stamp) — the adjudication that lifted
     * "no project anchor = forever unreachable" for identity-scoped reads like the project list.
     */
    private static final List<String> USER_ANCHOR_NAMES =
            List.of("userId", "uid", "operatorId", "actorId");

    /** Where the project anchor lives: path segment, query parameter or request-body field. */
    public static final String ANCHOR_PATH = "path";
    public static final String ANCHOR_QUERY = "query";
    public static final String ANCHOR_BODY = "body";

    /**
     * Name of the value that carries the project anchor — a declared parameter or, when the entry
     * takes its anchor inside the payload, a declared body field. Null when the endpoint has none.
     */
    public String projectAnchor() {
        for (String candidate : PROJECT_ANCHOR_NAMES) {
            for (ApiEndpointParam param : params) {
                if (candidate.equals(param.name()) && param.addressable()) {
                    return candidate;
                }
            }
        }
        for (String candidate : PROJECT_ANCHOR_NAMES) {
            if (bodyFields.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Name of the value that carries the acting-person anchor — a declared parameter or a declared
     * body field. Null when the endpoint addresses no user. {@link ApiAccessIdentityAnchor}
     * re-stamps this value from the trusted operator before the door composes the call, so an
     * identity-scoped entry never accepts a model-claimed person id.
     */
    public String userAnchor() {
        for (String candidate : USER_ANCHOR_NAMES) {
            for (ApiEndpointParam param : params) {
                if (candidate.equals(param.name()) && param.addressable()) {
                    return candidate;
                }
            }
        }
        for (String candidate : USER_ANCHOR_NAMES) {
            if (bodyFields.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Where this entry carries its project anchor, or null when it has none.
     *
     * <p>The landing door needs the distinction: a body-borne anchor is re-stamped server-side from
     * the chat session, while a path/query anchor must already match the session and is compared
     * instead of rewritten.
     */
    public String anchorCarrier() {
        String anchor = projectAnchor();
        if (anchor == null) {
            return null;
        }
        ApiEndpointParam param = param(anchor);
        if (param != null) {
            return param.in();
        }
        return bodyFields.contains(anchor) ? ANCHOR_BODY : null;
    }

    /** Whether a body-borne anchor can be re-stamped (the payload must declare it). */
    public boolean declaresField(String name) {
        return bodyFields.contains(name);
    }

    /** Whether the door can dispatch this endpoint at all, ignoring policy decisions. */
    public boolean dispatchable() {
        return unsupportedReason == null;
    }

    /**
     * True when the only reason the read door refuses this entry is that it takes a request body:
     * that is exactly what the change landing composes, so a body-shaped entry is not a dead end.
     */
    public boolean unsupportedBodyOnly() {
        return unsupportedReason != null && unsupportedReason.contains("request body");
    }

    /** Declared parameter by name (null when unknown, so callers can reject stray arguments). */
    public ApiEndpointParam param(String name) {
        for (ApiEndpointParam param : params) {
            if (param.name().equals(name)) {
                return param;
            }
        }
        return null;
    }
}
