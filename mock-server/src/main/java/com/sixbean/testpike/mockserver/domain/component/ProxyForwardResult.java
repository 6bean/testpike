package com.sixbean.testpike.mockserver.domain.component;

import java.util.Map;

/**
 * Outcome of one proxy forward . Either the real dependency
 * answered ({@code error} null, {@code status} the upstream code including
 * 4xx/5xx — an upstream problem rides through honestly) or the transport
 * failed ({@code status} null, {@code error} plain words for the 502 answer
 * and the recorded frame).
 *
 * @param status upstream status, null when the transport never answered
 * @param headers upstream response headers (hop-by-hop and length already removed)
 * @param body upstream body decoded as UTF-8 text (null/empty = no body)
 * @param error transport failure description, null on a real answer
 * @param latencyMs forward-and-answer wall time
 */
public record ProxyForwardResult(Integer status, Map<String, String> headers, String body,
                                 String error, long latencyMs) {

    /** True when the wire round-trip completed with an actual answer. */
    public boolean answered() {
        return error == null && status != null;
    }
}
