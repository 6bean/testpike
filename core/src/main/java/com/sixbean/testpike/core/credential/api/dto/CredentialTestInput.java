package com.sixbean.testpike.core.credential.api.dto;

import java.util.List;

/**
 * One-shot form for the credential vault "test now" door.
 *
 * @param url target address; blank = auto-fill from the newest
 * login state bound to this credential
 * @param method GET / POST / PUT
 * (the row's own key) are replaced at send time with the vault
 * material and NEVER appears in logs or events
 * @param headers repeatable request-header rows (the reference
 * surface): values may carry the same vault placeholder,
 * so a bearer/cookie credential rides the wire through
 * the header area even on GET with no body
 */
public record CredentialTestInput(String url, String method, String bodyTemplate,
                                  List<CredentialTestHeaderInput> headers) {

    /** Shape-compatible twin for callers that send no header rows. */
    public CredentialTestInput(String url, String method, String bodyTemplate) {
        this(url, method, bodyTemplate, List.of());
    }
}
