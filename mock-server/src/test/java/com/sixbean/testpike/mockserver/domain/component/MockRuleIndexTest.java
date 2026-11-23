package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mock.MockStubRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot-to-index mapping : the loader hands the pinned
 * {@code data.stubs[]} array to this class, which reduces it to kernel rules
 * plus the response payload each rule serves. Unknown/missing rows never
 * silently broaden matching — a row without an id is refused loudly so the
 * last good index survives instead.
 */
class MockRuleIndexTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SNAPSHOT_STUBS = """
            {"version":"v9",
             "stubs":[
               {"id":1,"name":"order detail","priority":10,"method":"GET",
                "pathMatchType":"template","pathPattern":"/orders/{id}",
                "matchersJson":"[]","responseJson":"{\\"status\\":200}"},
               {"id":2,"name":"catch all","priority":50,"method":"ANY",
                "pathMatchType":"prefix","pathPattern":"/","matchersJson":null,
                "responseJson":"{\\"status\\":204}"}
             ]}
            """;

    @Test
    void parsesSnapshotStubRowsIntoKernelRulesWithResponsePayload() throws Exception {
        List<MockLoadedStub> stubs = MockRuleIndex.parseStubs(
                MAPPER.readTree(SNAPSHOT_STUBS).path("stubs"));
        assertEquals(2, stubs.size(), "both rows must land in the index");
        MockStubRule first = stubs.get(0).rule();
        assertEquals(1L, first.stubId());
        assertEquals("order detail", first.name());
        assertEquals(10, first.priority());
        assertEquals("template", first.pathMatchType());
        assertEquals("/orders/{id}", first.pathPattern());
        assertEquals("[]", first.matchersJson());
        assertEquals("{\"status\":200}", stubs.get(0).responseJson());
        assertEquals("ANY", stubs.get(1).rule().method());
    }

    @Test
    void refusesRowsWithoutIdentityInsteadOfSilentlySkipping() throws Exception {
        var node = MAPPER.readTree(
                "{\"stubs\":[{\"name\":\"no id\",\"method\":\"GET\"}]}").path("stubs");
        assertThrows(IllegalArgumentException.class, () -> MockRuleIndex.parseStubs(node),
                "an unresolvable snapshot row must surface, never be dropped quietly");
    }

    @Test
    void swapKeepsLookupByStubIdAndRuleOrder() throws Exception {
        MockRuleIndex index = new MockRuleIndex();
        index.replaceAll(MockRuleIndex.parseStubs(
                MAPPER.readTree(SNAPSHOT_STUBS).path("stubs")));
        assertEquals(2, index.stubs().size());
        assertEquals("{\"status\":204}", index.responseJsonFor(2));
        assertNull(index.responseJsonFor(99), "unknown winner id has no payload");
        assertEquals(List.of("order detail", "catch all"),
                index.stubRules().stream().map(MockStubRule::name).toList());
    }

    @Test
    void emptySnapshotMeansEmptyIndexNotMissingOne() {
        MockRuleIndex index = new MockRuleIndex();
        assertTrue(index.stubRules().isEmpty(), "fresh index serves nothing yet");
    }
}
