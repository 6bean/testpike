package com.sixbean.testpike.core.aichat.api.dto;

import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;

import java.util.List;

/**
 * Start one dialog run through the internal session door. {@code userId} is
 * the asking user carried from the admin login context — ownership of the
 * session is re-checked here at the access point (AF-g), never trusted from
 * the caller's earlier calls. {@code refObjects}
 * carries the @-mention chips; core canonicalises them once
 * ({@code ChatObjectRefs.canonicalize}) before they ride the run message.
 * {@code attachments} carries the user-image
 * storage keys; likewise canonicalised once at core's single point
 * ({@code ChatImageAttachments}), old-shape requests simply omit it.
 * {@code systemManager} mirrors the vertical
 * verdict already decided on the admin session face — an absent field
 * deserialises to false, so an old caller gets the narrower member view.
 * {@code questionContext} is the whitelist page anchor
 * of THIS question — an optional JSON object string, validated at the core
 * run door through the same single point the session create uses; an absent
 * field keeps the exact old wire shape.
 */
public record StartRunRequest(long userId, String prompt, List<ChatObjectRef> refObjects,
                              List<ChatImageAttachment> attachments, boolean systemManager,
                              String questionContext) {

    /** Pre-identity-channel wire shape (bodies without the field): member view. */
    public StartRunRequest(long userId, String prompt, List<ChatObjectRef> refObjects,
                           List<ChatImageAttachment> attachments) {
        this(userId, prompt, refObjects, attachments, false, null);
    }

    /** Pre-question-context wire shape (bodies without the field): no anchor. */
    public StartRunRequest(long userId, String prompt, List<ChatObjectRef> refObjects,
                           List<ChatImageAttachment> attachments, boolean systemManager) {
        this(userId, prompt, refObjects, attachments, systemManager, null);
    }
}
