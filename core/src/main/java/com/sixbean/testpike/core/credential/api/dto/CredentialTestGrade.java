package com.sixbean.testpike.core.credential.api.dto;

/**
 * First-class evidence grade of one credential "test now" run .
 *
 * <p>The shared transport verdict (2xx / 401 / redirect) alone cannot answer
 * the user question "did the credential pass" - a 200 login-page shell or a
 * request that never carried the material must not read as a pass. The grade
 * combines the verdict, the reference count and the login-shell fingerprint;
 * it rides the outcome payload AND the persisted {@code tp_credential.test_evidence}
 * column so the list badge and the drawer agree.
 */
public enum CredentialTestGrade {
    /** Vault material was injected, the target answered 2xx, no shell page. */
    VERIFIED("凭据已验证"),
    /** Nothing referenced the vault: the address answered, the credential did not ride. */
    UNVERIFIED("凭据未被检验"),
    /** 401/403 or a redirect onto a login page: the target rejected the credential. */
    EXPIRED("凭据被拒"),
    /** Decisive neither way (non-2xx family or a 200 login-shell page). */
    UNKNOWN("结果需人工复核");

    private final String human;

    CredentialTestGrade(String human) {
        this.human = human;
    }

    /** Badge wording for the screen (user voice, kept in one place). */
    public String human() {
        return human;
    }
}
