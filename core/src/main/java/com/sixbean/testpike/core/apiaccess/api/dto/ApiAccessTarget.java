package com.sixbean.testpike.core.apiaccess.api.dto;

import java.util.List;

/**
 * A fully addressed request the pass-through door is allowed to make, plus what it consumed.
 *
 * @param requestUri     path with placeholders filled and the query string encoded
 * @param usedArguments argument names that were placed into the request (audit surface, values excluded)
 */
public record ApiAccessTarget(
        String requestUri,
        List<String> usedArguments) {
}
