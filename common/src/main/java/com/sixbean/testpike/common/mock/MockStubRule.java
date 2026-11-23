package com.sixbean.testpike.common.mock;

/**
 * One stub rule reduced to the fields the kernel adjudicates on . The
 * snapshot loader maps its pinned payload into this shape; keeping it a plain
 * record (no DO leak, no transport type) lets the same engine serve the save
 * chain legality check and the traffic plane without a second dialect.
 *
 * @param stubId database identity for trace/ledger reference
 * @param name human label shown in the inspector trace
 * @param priority ascending arbitration order (lower tries first)
 * @param method frozen verb set or ANY wildcard
 * @param pathMatchType exact / prefix / regex / template
 * @param pathPattern path expression, {var} captures for template type
 * @param matchersJson matcher row array (query/header/cookie/body), "[]" or
 * null = method+path only
 */
public record MockStubRule(
        long stubId,
        String name,
        int priority,
        String method,
        String pathMatchType,
        String pathPattern,
        String matchersJson) {
}
