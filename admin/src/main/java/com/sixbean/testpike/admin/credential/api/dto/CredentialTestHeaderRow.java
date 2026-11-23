package com.sixbean.testpike.admin.credential.api.dto;

/**
 * One repeatable request-header row of the vault "test now" dialog
 * . Mirror of the core input shape - the bridge forwards the
 * template untouched; vault placeholders resolve server-side only, so no
 * plaintext crosses this boundary in either direction.
 *
 * @param name  header name (token shape)
 * @param value header value template, may carry the vault placeholder
 */
public record CredentialTestHeaderRow(String name, String value) {
}
