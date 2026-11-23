package com.sixbean.testpike.core.common.domain.component;

import java.util.Map;

/**
 * Success-code pick shared by every contract-mapping consumer (request
 * assertion rows and mock stub response bodies): the lowest declared 2xx
 * response code is the happy path, falling back to 200 when the endpoint
 * declares no content-bearing success code. One rule, two callers — the
 * import mapper and the stub mapper must never drift apart on what "success"
 * means for the same contract.
 */
public final class FirstSuccessCode {

    private FirstSuccessCode() {
    }

    /** Lowest 2xx key of a response-code map, 200 when none is declared. */
    public static int pick(Map<String, String> responseSchemas) {
        int best = -1;
        for (String code : responseSchemas.keySet()) {
            if (code != null && code.length() == 3 && code.startsWith("2")) {
                int parsed = Integer.parseInt(code);
                if (best < 0 || parsed < best) {
                    best = parsed;
                }
            }
        }
        return best < 0 ? 200 : best;
    }
}
