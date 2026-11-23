package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

/**
 * JSON body of the core session door (mirrors core CreateSessionRequest
 * field-for-field). A separate type by the no-inner-class rule; {@code userId}
 * comes from the admin login context, never from the browser.
 */
record SessionCreatePayload(long projectId, long userId, String title, int source,
                            String contextJson) {
}
