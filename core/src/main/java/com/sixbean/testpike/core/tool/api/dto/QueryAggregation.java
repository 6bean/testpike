package com.sixbean.testpike.core.tool.api.dto;

import java.util.Set;

/**
 * One server-computed measure of a {@link QuerySpec}.
 * <p>{@code function} is a closed vocabulary ({@link #FUNCTIONS}:
 * count / sum / avg / min / max); {@code column} is null only for count(*),
 * otherwise a registered measure column of the target. The model declares
 * which measure over which column — the arithmetic itself runs on the server,
 * never in the answer text (A1 red line, mechanism-carried here).
 */
public record QueryAggregation(String function, String column) {

    /** Closed aggregation vocabulary — anything else is refused. */
    public static final Set<String> FUNCTIONS = Set.of("count", "sum", "avg", "min", "max");
}
