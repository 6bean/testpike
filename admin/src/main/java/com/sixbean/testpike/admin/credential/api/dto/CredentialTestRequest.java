package com.sixbean.testpike.admin.credential.api.dto;

/**
 * One-shot form for the vault "test now" door; the bridge carries it to
 * core verbatim. Placeholders {{CRED:key}} are only ever filled server-side
 * inside core - no plaintext crosses this boundary in either direction.
 */
public record CredentialTestRequest(String url, String method, String bodyTemplate,
                                    java.util.List<CredentialTestHeaderRow> headers) {

    /** Legacy shape: body-only reference, no header rows. */
    public CredentialTestRequest(String url, String method, String bodyTemplate) {
        this(url, method, bodyTemplate, java.util.List.of());
    }
}
