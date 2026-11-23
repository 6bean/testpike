package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.mock.MockStubRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory rule index of the traffic plane : the version-pinned
 * snapshot payload is parsed once into lookup-ready {@link MockLoadedStub} rows,
 * and every mocked request reads the current index through an immutable view.
 * <p>Swap is whole-index (build the new list, then publish) so a concurrent
 * request never sees a half-loaded rule set; a snapshot that fails to parse
 * keeps the previous index alive (serve-last-good, same discipline the pin
 * follows). Identity comes from the core row id — the traffic plane never
 * invents ids, so a missing one is a contract break worth surfacing.
 */
public class MockRuleIndex {

    /** Published view: ordered list for matching + id lookup for the payload. */
    private record Snapshot(List<MockLoadedStub> stubs, Map<Long, String> payloadById,
                            Map<String, MockScenarioMachine> scenarioMachines) {
    }

    private volatile Snapshot snapshot = new Snapshot(List.of(), Map.of(), Map.of());

    /** Current rules in snapshot order (core already sorts by priority, id). */
    public List<MockStubRule> stubRules() {
        Snapshot current = snapshot;
        List<MockStubRule> rules = new ArrayList<>(current.stubs().size());
        for (MockLoadedStub stub : current.stubs()) {
            rules.add(stub.rule());
        }
        return rules;
    }

    /** The whole loaded rows, exposed for diagnostics (inspector count). */
    public List<MockLoadedStub> stubs() {
        return snapshot.stubs();
    }

    /** Response payload of the winning rule, null when the id is unknown. */
    public String responseJsonFor(long stubId) {
        return snapshot.payloadById().get(stubId);
    }

    /** Scenario machines of the same swap — the stepping pre-filter reads these. */
    public Map<String, MockScenarioMachine> scenarioMachines() {
        return snapshot.scenarioMachines();
    }

    /**
     * Publish a freshly parsed rule set together with its machines, dropping
     * the previous pair atomically. Stubs and machines always swap as one
     * unit: a stub set stepped against a stale machine map would fire rules
     * onto states the new snapshot no longer declares.
     */
    public void replaceAll(List<MockLoadedStub> stubs) {
        replaceAll(stubs, Map.of());
    }

    /** Whole-swap publish with scenario machines . */
    public void replaceAll(List<MockLoadedStub> stubs, Map<String, MockScenarioMachine> machines) {
        Map<Long, String> payloadById = new HashMap<>(stubs.size() * 2);
        for (MockLoadedStub stub : stubs) {
            payloadById.put(stub.rule().stubId(), stub.responseJson());
        }
        this.snapshot = new Snapshot(List.copyOf(stubs), Map.copyOf(payloadById),
                Map.copyOf(machines));
    }

    /**
     * Reduce the snapshot {@code stubs[]} array into loaded rules. Missing
     * identity is refused rather than skipped: silently dropping a rule would
     * change which stub answers traffic while the pin claims the set loaded.
     */
    public static List<MockLoadedStub> parseStubs(JsonNode stubsNode) {
        List<MockLoadedStub> out = new ArrayList<>();
        if (stubsNode == null || !stubsNode.isArray()) {
            return out;
        }
        for (JsonNode row : stubsNode) {
            long stubId = row.path("id").asLong(-1);
            if (stubId <= 0) {
                throw new IllegalArgumentException(
                        "快照规则行缺少 id（name=" + row.path("name").asText("?")
                                + "），装载中止以上一轮规则继续服务");
            }
            MockStubRule rule = new MockStubRule(stubId,
                    row.path("name").asText("stub-" + stubId),
                    row.path("priority").asInt(100),
                    row.path("method").asText("ANY"),
                    row.path("pathMatchType").asText("exact"),
                    row.path("pathPattern").asText("/"),
                    row.path("matchersJson").asText(null));
            out.add(new MockLoadedStub(rule, row.path("responseJson").asText(null),
                    row.path("scenarioName").asText(null),
                    row.path("requiredState").asText(null),
                    row.path("newState").asText(null)));
        }
        return List.copyOf(out);
    }
}
