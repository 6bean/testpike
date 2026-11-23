package com.sixbean.testpike.core.batch.api.dto;

/**
 * CI startup environment override input parameter: the batch start interface accepts an optional envOverrides field as a contract change.
 * <p>If both fields are null, it is equivalent to no override (Web requests with no body are unaffected); the value domain is gated (length/protocol/no line breaks/no placeholders).
 * This is a single point in BatchService's private component; accountRef is a credential reference, and plaintext credentials are forbidden.
 * The audit stores only the reference string; the override URL is not persisted to tp_project_env and is written only to the task snapshot env_base_url.
 *
 * @param url the override execution base URL (http/https, ≤512, no line breaks or placeholder syntax)
 * @param accountRef the credential reference identifier (≤128, same character-set gate)
 */
public record EnvOverrideInput(String url, String accountRef) {

    /** The valid override determination is a single point (any field non-null counts as an override; all null is equivalent to no override; Controller dispatch and Impl audit use the same determination). */
    public boolean hasAny() {
        return url != null || accountRef != null;
    }
}
