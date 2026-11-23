package com.sixbean.testpike.core.apiaccess.api.dto;

import java.util.List;

/**
 * A change request the landing door is allowed to send after a human confirmed the card.
 *
 * @param requestUri    path with placeholders filled plus the encoded query string
 * @param httpMethod    the method the catalog entry declares (POST/PUT/DELETE/PATCH)
 * @param bodyJson      composed body built only from the declared fields of the entry's payload
 *                      type; null when the entry has no payload
 * @param usedArguments argument names consumed by the request (audit surface, values excluded)
 */
public record ApiAccessWriteTarget(
        String requestUri,
        String httpMethod,
        String bodyJson,
        List<String> usedArguments) {
}
