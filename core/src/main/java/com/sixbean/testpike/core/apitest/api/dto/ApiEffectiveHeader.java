package com.sixbean.testpike.core.apitest.api.dto;

/**
 * One merged header row of the effective-defaults preview .
 *
 * @param name header name as it will go on the wire (winner keeps its casing:
 * a request row beats a collection default on a same-name pair,
 * compared case-insensitively per HTTP header semantics)
 * @param value header value as stored (template literals {{ENV:}}/{{gen.*}}
 * pass through unrendered — the preview shows the stored truth,
 * not the executor-side render)
 * @param source which face the row came from: {@code request} or {@code collection}
 */
public record ApiEffectiveHeader(String name, String value, String source) {
}
