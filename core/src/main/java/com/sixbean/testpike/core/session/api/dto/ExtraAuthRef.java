package com.sixbean.testpike.core.session.api.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * One combined-session header a login state rides alongside its primary
 * kind header . The row carries a reference, never a secret:
 * {@code credKey} points at a credential already registered in this
 * project's vault (single ciphertext source), and {@code valueTemplate}
 * is an optional prefix template in the shared {@code {{CRED:key}}}
 * grammar (e.g. "Bearer {{CRED:api_key}}") — free-text values that could
 * hide plaintext secrets are refused at save time. {@code into} is
 * header-only by design; the server rejects anything else.
 */
public record ExtraAuthRef(String credKey, String into, String headerName, String valueTemplate) {

    /** The only accepted transport destination for a combined header. */
    public static final String INTO_HEADER = "header";

    /** Placeholder every legal value template must reference (vault single source). */
    public static final String CRED_PLACEHOLDER_PREFIX = "{{CRED:";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Serializes the ref list into the jsonb column payload ("[]" for none). */
    public static String writeJson(List<ExtraAuthRef> refs) {
        List<ExtraAuthRef> values = refs == null ? List.of() : refs;
        try {
            return JSON.writeValueAsString(values);
        } catch (Exception failed) {
            throw new IllegalStateException("附加会话头清单无法序列化", failed);
        }
    }

    /** Reads the jsonb column text back; malformed or absent json reads as empty (fail-closed). */
    public static List<ExtraAuthRef> readJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ExtraAuthRef> parsed = JSON.readValue(json, new TypeReference<List<ExtraAuthRef>>() { });
            List<ExtraAuthRef> kept = new ArrayList<>();
            for (ExtraAuthRef ref : parsed) {
                if (ref != null && ref.credKey() != null && !ref.credKey().isBlank()) {
                    kept.add(ref);
                }
            }
            return List.copyOf(kept);
        } catch (Exception parseFailed) {
            return List.of();
        }
    }
}
