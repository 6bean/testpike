package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

/**
 * JSON body of the core steering door (mirrors core {@code InjectUserRequest}
 * field-for-field). A separate type by the no-inner-class rule; {@code userId}
 * comes from the admin login context, never from the browser, and core
 * re-checks session ownership against it.
 */
record InjectUserPayload(long userId, String content) {
}
