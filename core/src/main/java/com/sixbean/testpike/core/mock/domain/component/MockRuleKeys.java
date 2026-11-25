package com.sixbean.testpike.core.mock.domain.component;

import java.util.Locale;

/**
 * Single source of the rule-identity key (uppercase verb + trimmed path)
 * that answers "does this space already serve this endpoint".
 * Two consumers share it — the contract-direct import's idempotence check
 * ({@code MockStubImportServiceImpl}) and the pre-adjudication layer's conflict
 * detection ({@code SpecImportVerdictServiceImpl}) — so the normalization can
 * never drift apart between "skip" behaviour and "avoid" advice.
 */
public final class MockRuleKeys {

    private MockRuleKeys() {
    }

    public static String of(String method, String pathPattern) {
        return (method == null ? "" : method.trim().toUpperCase(Locale.ROOT))
                + " " + (pathPattern == null ? "" : pathPattern.trim());
    }
}
