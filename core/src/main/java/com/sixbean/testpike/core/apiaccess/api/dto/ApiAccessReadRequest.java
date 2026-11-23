package com.sixbean.testpike.core.apiaccess.api.dto;

import java.util.Map;

/**
 * Read request against the pass-through door.
 *
 * <p>The acting identity is deliberately absent: it is carried by the internal operator header and
 * stamped by the caller chain, never composed by the model. The same goes for the task id, which
 * rides the same trusted header/parameter seam as tool invocation.
 *
 * @param endpointKey catalog key of the read to perform
 * @param arguments   declared parameters only (path placeholders and query parameters)
 */
public record ApiAccessReadRequest(
        String endpointKey,
        Map<String, Object> arguments) {
}
