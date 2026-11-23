package com.sixbean.testpike.core.session;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shared builder for the well-formed unsigned JWT fixture used by the
 * login-state specs. The ledger only needs a payload it can parse an expiry
 * from, so the signature segment is a constant; keeping the encoder in one
 * place stops the two specs from drifting apart.
 */
public final class SessionJwtFixture {

    private SessionJwtFixture() {
    }

    /** Base64url header/payload pair carrying {@code exp} at the given epoch second. */
    public static String signedWithExp(long epochSeconds) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"exp\":" + epochSeconds + "}")
                        .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".c2ln";
    }
}
