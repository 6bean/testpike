package com.sixbean.testpike.core.apiaccess.api.dto;

import java.util.Map;

/**
 * A confirmed change proposal handed to the landing door.
 *
 * @param endpointKey catalog key of the change entry
 * @param arguments   declared parameters and payload fields (the owning project is re-stamped by
 *                    the landing door from the chat session, never taken from here)
 */
public record ApiAccessWriteRequest(
        String endpointKey,
        Map<String, Object> arguments) {
}
