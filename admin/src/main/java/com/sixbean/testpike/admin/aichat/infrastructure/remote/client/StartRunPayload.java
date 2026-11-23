package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;

import java.util.List;

/**
 * JSON body of the core run door (mirrors core StartRunRequest field-for-field).
 * A separate type by the no-inner-class rule; {@code userId} comes from the
 * admin login context, never from the browser. {@code refObjects} (AO batch
 * knife 4b) carries the @-mention chips through to core's single
 * canonicalisation point — admin never validates them a second time.
 * {@code attachments} mirrors the user-image
 * storage keys the same mirror-only way. {@code systemManager} (AO batch
 * S4 mirrors the vertical verdict decided once from the admin
 * session roles — core and the worker never re-derive it. {@code questionContext}
 * mirrors the send-time page anchor the same
 * mirror-only way; core's whitelist door is the single validation point.
 */
record StartRunPayload(long userId, String prompt, List<ChatObjectRef> refObjects,
                       List<ChatImageAttachment> attachments, boolean systemManager,
                       String questionContext) {
}
