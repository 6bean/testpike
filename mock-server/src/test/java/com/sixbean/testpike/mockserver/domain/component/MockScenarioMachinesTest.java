package com.sixbean.testpike.mockserver.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mock.MockScenarioRules;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.sixbean.testpike.mockserver.domain.component.MockScenarioMachine;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Load-time contract of the stateful scenario machines :
 * snapshot scenario rows reduce into lane-ready machine records with the
 * declared-state set from the shared kernel (no second dialect), a broken
 * machine refuses the whole batch so the traffic plane keeps serving from
 * the last good pin, and the loaded stub rows carry their three scenario
 * columns for the controller's pre-filter. The rule loader publishes
 * machines in the same swap as stubs — a half-loaded stepping set is the
 * worst of both worlds.
 */
class MockScenarioMachinesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL = CORE_URL + "/internal/mock/snapshot?spaceKey=demo";

    /** One checkout machine (session lane default) + one stateless global machine. */
    private static final String SNAPSHOT_WITH_SCENARIOS = """
            {"code":"0","data":{"version":"v2","space":{"spaceKey":"demo"},
             "stubs":[
               {"id":1,"name":"create order","priority":10,"method":"POST",
                "pathMatchType":"exact","pathPattern":"/orders","matchersJson":"[]",
                "responseJson":"{\\"status\\":200,\\"bodyMode\\":\\"static\\",\\"body\\":\\"made\\"}",
                "scenarioName":"checkout","requiredState":null,"newState":"STARTED"},
               {"id":2,"name":"pay order","priority":20,"method":"POST",
                "pathMatchType":"exact","pathPattern":"/pay","matchersJson":"[]",
                "responseJson":"{\\"status\\":201,\\"bodyMode\\":\\"static\\",\\"body\\":\\"paid\\"}",
                "scenarioName":"checkout","requiredState":"STARTED","newState":"PAID"}
             ],
             "scenarios":[
               {"id":7,"spaceId":1,"name":"checkout",
                "definitionJson":"{\\"states\\":[\\"STARTED\\",\\"PAID\\"],\\"initial\\":\\"STARTED\\"}"},
               {"id":8,"spaceId":1,"name":"flagboard",
                "definitionJson":"{\\"states\\":[\\"ON\\",\\"OFF\\"],\\"scope\\":\\"global\\"}"}
             ]}}
            """;

    /** Same shape, newer version, second machine's state list empty — unparsable declaration. */
    private static final String SNAPSHOT_WITH_BROKEN_SCENARIO = SNAPSHOT_WITH_SCENARIOS
            .replace("\"version\":\"v2\"", "\"version\":\"v3\"")
            .replace("{\\\"states\\\":[\\\"ON\\\",\\\"OFF\\\"],\\\"scope\\\":\\\"global\\\"}",
                    "{\\\"states\\\":[]}")
            .replace("flagboard", "broken-machine");

    @Test
    void parsesMachinesWithDeclaredStatesInitialAndScopeDefaults() throws Exception {
        JsonNode scenarios = MAPPER.readTree(SNAPSHOT_WITH_SCENARIOS)
                .path("data").path("scenarios");
        MockScenarioMachines machines = new MockScenarioMachines(new MockScenarioRules());
        Map<String, MockScenarioMachine> parsed = machines.parse(scenarios);

        assertEquals(List.of("checkout", "flagboard"), List.copyOf(parsed.keySet()),
                "declaration order is preserved");
        MockScenarioMachine checkout = parsed.get("checkout");
        assertEquals("session", checkout.scope(), "M-c ruling: session lane is the default");
        assertEquals("STARTED", checkout.initialState());
        assertEquals(List.of("STARTED", "PAID"), List.copyOf(checkout.declaredStates()),
                "the declared set rides the shared kernel, in declaration order");

        MockScenarioMachine flagboard = parsed.get("flagboard");
        assertEquals("global", flagboard.scope());
        assertEquals("ON", flagboard.initialState(),
                "absent initial falls back to the first declared state");
    }

    @Test
    void refusesMachineRowsWithoutAName() throws Exception {
        JsonNode scenarios = MAPPER.readTree(
                "{\"scenarios\":[{\"definitionJson\":\"{\\\"states\\\":[\\\"A\\\"]}\"}]}")
                .path("scenarios");
        MockScenarioMachines machines = new MockScenarioMachines(new MockScenarioRules());
        assertThrows(IllegalArgumentException.class, () -> machines.parse(scenarios),
                "a machine nobody can name cannot hold lane state — refuse, never skip");
    }

    @Test
    void brokenMachineRefusesTheWholeBatchSoTheOldPinKeepsServing() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_WITH_SCENARIOS, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        assertTrue(loader.everLoaded(), "the first good snapshot pins");

        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_WITH_BROKEN_SCENARIO, MediaType.APPLICATION_JSON));
        loader.refresh();
        assertEquals("v2", loader.pinnedVersion(),
                "a snapshot with an unparsable machine never replaces the pin");
        assertEquals(2, loader.ruleIndex().stubs().size(), "old stub set stands");
        assertTrue(loader.ruleIndex().scenarioMachines().containsKey("checkout"),
                "old machine set stands in the same swap as the stubs");
    }

    @Test
    void loaderPublishesMachinesTogetherWithStubsOnDrift() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_WITH_SCENARIOS, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();

        assertEquals("v2", loader.pinnedVersion());
        assertEquals(2, loader.ruleIndex().scenarioMachines().size());
        List<MockLoadedStub> loaded = loader.ruleIndex().stubs();
        assertEquals("checkout", loaded.get(0).scenarioName());
        assertEquals("STARTED", loaded.get(1).requiredState());
        assertEquals("PAID", loaded.get(1).newState());
    }

    @Test
    void statelessSnapshotYieldsEmptyMachineMapAndNullColumns() throws Exception {
        JsonNode root = MAPPER.readTree("""
                {"stubs":[{"id":3,"name":"plain","priority":10,"method":"GET",
                           "pathMatchType":"exact","pathPattern":"/x","matchersJson":"[]",
                           "responseJson":"{\\"status\\":200}"}]}
                """);
        List<MockLoadedStub> loaded = MockRuleIndex.parseStubs(root.path("stubs"));
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).scenarioName() == null
                        && loaded.get(0).requiredState() == null
                        && loaded.get(0).newState() == null,
                "absent scenario columns read as null — stateless rules stay unconditional candidates");
    }
}
