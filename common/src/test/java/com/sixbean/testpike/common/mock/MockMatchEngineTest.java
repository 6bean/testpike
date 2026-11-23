package com.sixbean.testpike.common.mock;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matching kernel contract suite : the frozen matcher key set —
 * method wildcard ANY, path four types (exact/prefix/regex/template with
 * {var} capture), per-key query, header (case-insensitive), cookie and the
 * five body matcher families (exact/contains/regex/jsonpath/form). Multi-stub
 * arbitration = ascending priority, first full match wins; every evaluated
 * stub leaves a trace line so the inspector can explain "why did my mock not
 * fire" without a second dialect anywhere (JsonPath rides the shared
 * restricted subset of NonUiActionContract).
 */
class MockMatchEngineTest {

    private final MockMatchEngine engine = new MockMatchEngine();

    private static MockMatchRequest jsonRequest(String method, String path, String body) {
        return new MockMatchRequest(method, path, Map.of(), Map.of(), Map.of(),
                body, "application/json");
    }

    private static MockStubRule stub(long id, String name, int priority, String method,
                                     String pathMatchType, String pathPattern, String matchersJson) {
        return new MockStubRule(id, name, priority, method, pathMatchType, pathPattern, matchersJson);
    }

    // ---- method ----

    @Test
    void anyMethodMatchesEveryVerb() {
        MockStubRule rule = stub(1, "any", 10, "ANY", "exact", "/x", "[]");
        MockMatchVerdict verdict = engine.match(jsonRequest("POST", "/x", "{}"), List.of(rule));
        assertTrue(verdict.matched());
        assertEquals("any", verdict.stubName());
    }

    @Test
    void methodMismatchSkipsTheStubAndStaysVisibleInTrace() {
        MockStubRule rule = stub(1, "get-only", 10, "GET", "exact", "/x", "[]");
        MockMatchVerdict verdict = engine.match(jsonRequest("DELETE", "/x", "{}"), List.of(rule));
        assertFalse(verdict.matched());
        assertTrue(verdict.traces().stream().anyMatch(t -> "FAIL".equals(t.outcome())
                && t.step().equals("method")));
    }

    // ---- path four types ----

    @Test
    void pathExactRequiresFullEqualityAfterTrailingSlashNormalisation() {
        MockStubRule rule = stub(1, "exact", 10, "ANY", "exact", "/orders", "[]");
        assertTrue(engine.match(jsonRequest("GET", "/orders/", ""), List.of(rule)).matched());
        assertFalse(engine.match(jsonRequest("GET", "/orders/1", ""), List.of(rule)).matched());
    }

    @Test
    void pathPrefixMatchesEverythingBelow() {
        MockStubRule rule = stub(1, "prefix", 10, "ANY", "prefix", "/api", "[]");
        assertTrue(engine.match(jsonRequest("GET", "/api/v1/orders", ""), List.of(rule)).matched());
        assertFalse(engine.match(jsonRequest("GET", "/apix", ""), List.of(rule)).matched());
    }

    @Test
    void pathRegexUsesFindSemanticsLikeEveryOtherRegexRow() {
        MockStubRule rule = stub(1, "regex", 10, "ANY", "regex", "/orders/\\d+$", "[]");
        assertTrue(engine.match(jsonRequest("GET", "/orders/42", ""), List.of(rule)).matched());
        assertFalse(engine.match(jsonRequest("GET", "/orders/abc", ""), List.of(rule)).matched());
    }

    @Test
    void pathTemplateCapturesVariablesForTheResponseTemplate() {
        MockStubRule rule = stub(1, "tpl", 10, "ANY", "template", "/users/{userId}/orders/{oid}", "[]");
        MockMatchVerdict verdict = engine.match(jsonRequest("GET", "/users/42/orders/7", ""), List.of(rule));
        assertTrue(verdict.matched());
        assertEquals("42", verdict.pathVars().get("userId"));
        assertEquals("7", verdict.pathVars().get("oid"));
    }

    @Test
    void pathTemplateLiteralSegmentMismatchFails() {
        MockStubRule rule = stub(1, "tpl", 10, "ANY", "template", "/users/{userId}/orders", "[]");
        MockMatchVerdict verdict = engine.match(jsonRequest("GET", "/users/42/carts", ""), List.of(rule));
        assertFalse(verdict.matched());
    }

    // ---- query / header / cookie ----

    @Test
    void queryMissingKeyFailsAndPresentKeyMatchesByExactValue() {
        String matchers = "[{\"kind\":\"query\",\"key\":\"page\",\"matchType\":\"exact\",\"value\":\"2\"}]";
        MockStubRule rule = stub(1, "q", 10, "ANY", "prefix", "/x", matchers);
        MockMatchRequest hit = new MockMatchRequest("GET", "/x", Map.of("page", List.of("2")),
                Map.of(), Map.of(), null, null);
        assertTrue(engine.match(hit, List.of(rule)).matched());
        MockMatchRequest miss = new MockMatchRequest("GET", "/x", Map.of("page", List.of("3")),
                Map.of(), Map.of(), null, null);
        MockMatchVerdict verdict = engine.match(miss, List.of(rule));
        assertFalse(verdict.matched());
        assertTrue(verdict.traces().stream().anyMatch(t -> t.detail().contains("3")));
    }

    @Test
    void queryAnyValueOfMultiValueKeyMayMatchAndExistsModeNeedsNoValue() {
        String matchers = "[{\"kind\":\"query\",\"key\":\"tag\",\"matchType\":\"regex\",\"value\":\"^red\"},"
                + "{\"kind\":\"query\",\"key\":\"sid\",\"matchType\":\"exists\"}]";
        MockStubRule rule = stub(1, "q2", 10, "ANY", "exact", "/x", matchers);
        MockMatchRequest req = new MockMatchRequest("GET", "/x",
                Map.of("tag", List.of("blue", "red-1"), "sid", List.of("")),
                Map.of(), Map.of(), null, null);
        assertTrue(engine.match(req, List.of(rule)).matched());
    }

    @Test
    void headerLookupIsCaseInsensitiveOnBothSides() {
        String matchers = "[{\"kind\":\"header\",\"key\":\"x-trace\",\"matchType\":\"exact\",\"value\":\"T1\"}]";
        MockStubRule rule = stub(1, "h", 10, "ANY", "exact", "/x", matchers);
        MockMatchRequest req = new MockMatchRequest("GET", "/x", Map.of(),
                Map.of("X-Trace", "T1"), Map.of(), null, null);
        assertTrue(engine.match(req, List.of(rule)).matched());
    }

    @Test
    void cookieMatchersCompareByName() {
        String matchers = "[{\"kind\":\"cookie\",\"key\":\"sid\",\"matchType\":\"contains\",\"value\":\"abc\"}]";
        MockStubRule rule = stub(1, "c", 10, "ANY", "exact", "/x", matchers);
        MockMatchRequest hit = new MockMatchRequest("GET", "/x", Map.of(), Map.of(),
                Map.of("sid", "xabcy"), null, null);
        MockMatchRequest miss = new MockMatchRequest("GET", "/x", Map.of(), Map.of(),
                Map.of("sid", "zzz"), null, null);
        assertTrue(engine.match(hit, List.of(rule)).matched());
        assertFalse(engine.match(miss, List.of(rule)).matched());
    }

    // ---- body five families ----

    @Test
    void bodyExactAndContainsCompareTheRawPayload() {
        String exact = "[{\"kind\":\"body\",\"bodyType\":\"exact\",\"value\":\"{\\\"a\\\":1}\"}]";
        assertTrue(engine.match(jsonRequest("POST", "/x", "{\"a\":1}"),
                List.of(stub(1, "be", 10, "ANY", "exact", "/x", exact))).matched());
        String contains = "[{\"kind\":\"body\",\"bodyType\":\"contains\",\"value\":\"PAID\"}]";
        assertTrue(engine.match(jsonRequest("POST", "/x", "{\"status\":\"PAID\"}"),
                List.of(stub(2, "bc", 10, "ANY", "exact", "/x", contains))).matched());
    }

    @Test
    void bodyRegexUsesFindSemantics() {
        String matchers = "[{\"kind\":\"body\",\"bodyType\":\"regex\",\"value\":\"count=\\\\d{3}\"}]";
        assertTrue(engine.match(new MockMatchRequest("POST", "/x", Map.of(), Map.of(), Map.of(),
                        "prefix count=123 suffix", "text/plain"),
                List.of(stub(1, "br", 10, "ANY", "exact", "/x", matchers))).matched());
    }

    @Test
    void bodyJsonPathEqualsExistsAndTypeAgainstTheRestrictedSubset() {
        String path = "$.data.items[0].status";
        String json = "{\"data\":{\"items\":[{\"status\":\"PAID\"}]}}";
        assertTrue(engine.match(jsonRequest("POST", "/x", json), List.of(stub(1, "j1", 10,
                "ANY", "exact", "/x", "[{\"kind\":\"body\",\"bodyType\":\"jsonpath\",\"path\":\""
                        + path + "\",\"assertMode\":\"equals\",\"value\":\"PAID\"}]"))).matched());
        assertFalse(engine.match(jsonRequest("POST", "/x", json), List.of(stub(2, "j2", 10,
                "ANY", "exact", "/x", "[{\"kind\":\"body\",\"bodyType\":\"jsonpath\",\"path\":\""
                        + path + "\",\"assertMode\":\"equals\",\"value\":\"NEW\"}]"))).matched());
        assertTrue(engine.match(jsonRequest("POST", "/x", json), List.of(stub(3, "j3", 10,
                "ANY", "exact", "/x", "[{\"kind\":\"body\",\"bodyType\":\"jsonpath\",\"path\":\""
                        + path + "\",\"assertMode\":\"exists\"}]"))).matched());
        assertTrue(engine.match(jsonRequest("POST", "/x", json), List.of(stub(4, "j4", 10,
                "ANY", "exact", "/x", "[{\"kind\":\"body\",\"bodyType\":\"jsonpath\",\"path\":\"$.data\","
                        + "\"assertMode\":\"type\",\"value\":\"object\"}]"))).matched());
    }

    @Test
    void jsonPathOutsideTheRestrictedSubsetFailsClosedWithoutInventingADialect() {
        // filter / recursive descent are banned by NonUiActionContract — the kernel
        // must refuse the row, never guess an interpretation.
        MockMatchVerdict verdict = engine.match(jsonRequest("POST", "/x", "{\"a\":1}"),
                List.of(stub(1, "bad-path", 10, "ANY", "exact", "/x",
                        "[{\"kind\":\"body\",\"bodyType\":\"jsonpath\",\"path\":\"$..a\","
                                + "\"assertMode\":\"exists\"}]")));
        assertFalse(verdict.matched());
        assertTrue(verdict.traces().stream().anyMatch(t -> t.detail().contains("受限")));
    }

    @Test
    void bodyFormMatcherDecodesUrlEncodedFields() {
        String matchers = "[{\"kind\":\"body\",\"bodyType\":\"form\",\"key\":\"name\","
                + "\"matchType\":\"exact\",\"value\":\"张三 & co\"}]";
        MockMatchRequest req = new MockMatchRequest("POST", "/x", Map.of(), Map.of(), Map.of(),
                "name=%E5%BC%A0%E4%B8%89%20%26%20co&qty=2", "application/x-www-form-urlencoded");
        assertTrue(engine.match(req, List.of(stub(1, "form", 10, "ANY", "exact", "/x", matchers))).matched());
    }

    // ---- arbitration / fail-closed ----

    @Test
    void ascendingPriorityFirstFullMatchWinsAndEarlierLoserLeavesTrace() {
        List<MockStubRule> rules = List.of(
                stub(2, "second", 20, "ANY", "exact", "/x",
                        "[{\"kind\":\"body\",\"bodyType\":\"contains\",\"value\":\"PAID\"}]"),
                stub(1, "first-loser", 10, "ANY", "exact", "/x",
                        "[{\"kind\":\"body\",\"bodyType\":\"contains\",\"value\":\"NEW\"}]"));
        MockMatchVerdict verdict = engine.match(jsonRequest("POST", "/x", "{\"s\":\"PAID\"}"), rules);
        assertTrue(verdict.matched());
        assertEquals("second", verdict.stubName());
        assertTrue(verdict.traces().stream()
                .anyMatch(t -> "first-loser".equals(t.stubName()) && "FAIL".equals(t.outcome())));
    }

    @Test
    void unparsableMatchersFailClosedInsteadOfMatchingEverything() {
        MockMatchVerdict verdict = engine.match(jsonRequest("GET", "/x", ""),
                List.of(stub(1, "broken", 10, "ANY", "exact", "/x", "{not json")));
        assertFalse(verdict.matched());
        assertTrue(verdict.traces().stream().anyMatch(t -> t.detail().contains("matchers")));
    }

    @Test
    void emptyMatchersMeansPathAndMethodOnly() {
        assertTrue(engine.match(jsonRequest("GET", "/x", ""),
                List.of(stub(1, "bare", 10, "GET", "exact", "/x", null))).matched());
    }

    // ---- save-chain row legality (same key set, no second validator) ----

    @Test
    void matcherRowViolationAcceptsTheFrozenKeySetAndRejectsUnknownKind() {
        assertNull(engine.matcherRowViolation(
                "[{\"kind\":\"query\",\"key\":\"a\",\"matchType\":\"exact\",\"value\":\"1\"}]"));
        assertNull(engine.matcherRowViolation("[]"));
        assertNull(engine.matcherRowViolation(null));
        assertNotNull(engine.matcherRowViolation(
                "[{\"kind\":\"xpath\",\"key\":\"a\"}]"));
        assertNotNull(engine.matcherRowViolation(
                "[{\"kind\":\"body\",\"bodyType\":\"script\",\"value\":\"1\"}]"));
        assertNotNull(engine.matcherRowViolation(
                "[{\"kind\":\"query\",\"key\":\"a\",\"matchType\":\"script\",\"value\":\"1\"}]"));
    }
}
