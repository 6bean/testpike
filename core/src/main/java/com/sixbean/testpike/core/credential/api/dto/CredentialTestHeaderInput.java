package com.sixbean.testpike.core.credential.api.dto;

/**
 * One repeatable request-header row of the vault "test now" dialog
 * (the reference surface). Values may carry the vault placeholder
 * {@code {{CRED:credKey}}}; the real material is injected server-side only
 * and never crosses this boundary in plaintext in either direction.
 *
 * @param name  header name (token shape, no colon / CR / LF)
 * @param value header value template, placeholders resolved before send
 */
public record CredentialTestHeaderInput(String name, String value) {
}
