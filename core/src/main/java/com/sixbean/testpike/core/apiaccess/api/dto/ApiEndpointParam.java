package com.sixbean.testpike.core.apiaccess.api.dto;

/**
 * One addressable parameter of a cataloged endpoint.
 *
 * @param name     parameter name as the caller must spell it in the arguments object
 * @param in       binding location: {@code path}, {@code query}, or {@code other}
 *                 (header/attribute/multipart values the caller cannot address)
 * @param required true when the endpoint rejects the call without this value
 * @param type         declared Java type, simplified to its component name for display
 * @param defaultValue server-side default when the caller omits the value, null when there is none
 */
public record ApiEndpointParam(
        String name,
        String in,
        boolean required,
        String type,
        String defaultValue) {

    /** Binding location constants (kept as strings because they travel in tool schemas). */
    public static final String IN_PATH = "path";
    public static final String IN_QUERY = "query";
    public static final String IN_OTHER = "other";

    /** Whether the caller can supply this parameter through the arguments object. */
    public boolean addressable() {
        return IN_PATH.equals(in) || IN_QUERY.equals(in);
    }
}
