package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.mock.MockScenarioRules;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reduces snapshot {@code scenarios[]} rows into lane-ready
 * {@link MockScenarioMachine} records . Validation rides the
 * shared declaration kernel — the same component the save chain refuses
 * broken machines with — so the traffic plane can never accept a machine the
 * management plane would have rejected (single-dialect red line).
 * <p>Load-time strictness: an unparsable row aborts the whole batch (the
 * loader keeps serving from the last good pin). Silently skipping one machine
 * would leave its stub rows pre-filtered against a missing machine — traffic
 * quietly going 404 is exactly the failure mode the honesty rules forbid.
 */
@Component
public class MockScenarioMachines {

    /** Definition column is JSON-in-text; one reader for the whole component. */
    private static final com.fasterxml.jackson.databind.ObjectMapper DEFINITION_READER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final MockScenarioRules scenarioRules;

    public MockScenarioMachines(MockScenarioRules scenarioRules) {
        this.scenarioRules = scenarioRules;
    }

    /** Parse every declared machine, keeping declaration order for diagnostics. */
    public Map<String, MockScenarioMachine> parse(JsonNode scenariosNode) {
        Map<String, MockScenarioMachine> out = new LinkedHashMap<>();
        if (scenariosNode == null || !scenariosNode.isArray()) {
            return out;
        }
        for (JsonNode row : scenariosNode) {
            String name = row.path("name").asText(null);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("快照场景行缺少 name——无名机器无法承载泳道状态，"
                        + "装载中止以上一轮场景继续服务");
            }
            Set<String> declared = scenarioRules.declaredStates(row.path("definitionJson").asText(""));
            JsonNode definition = parseDefinition(row.path("definitionJson").asText("{}"));
            String initial = definition.path("initial").asText(null);
            String scope = definition.path("scope").asText(null);
            out.put(name, new MockScenarioMachine(name,
                    scope == null ? "session" : scope,
                    initial == null ? List.copyOf(declared).get(0) : initial,
                    declared));
        }
        return out;
    }

    /** Definition text arrives as JSON-in-a-column; parse once, kernel already proved it valid. */
    private JsonNode parseDefinition(String definitionJson) {
        try {
            return DEFINITION_READER.readTree(definitionJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("快照场景行的 definitionJson 无法解析：" + e.getMessage(), e);
        }
    }
}
