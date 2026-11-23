package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.common.util.TlsPolicy;

import java.util.List;
import java.util.Map;

/**
 * Four-channel parameter view of the claim envelope (unified output parameter shape shared by snapshot-pinned version and real-time reverse lookup).
 * <p>envVars=plaintext environment parameter map; connections=list of enabled connections; dataVars=plaintext map of available data-pool items.
 * tlsPolicy=TLS three-tier decision; the visual baseline is not included (real-time observation semantic domain, retaining the current claim assembly path).
 * <p>⚠️ Each field contains plaintext credentials—for claim dispatch in-memory transfer only; must not be logged or audited.
 *
 * @param envVars environment parameters (env.vars)
 * @param connections database connections
 * @param dataVars data-pool variables
 * @param tlsPolicy TLS decision
 */
public record BatchParamView(Map<String, String> envVars,
                             List<Map<String, Object>> connections,
                             Map<String, String> dataVars,
                             TlsPolicy tlsPolicy) {
}
