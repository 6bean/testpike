package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInfo;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stateful scenario declaration plane .
 * <p>Verified behaviour against the real dev database (SpringBootTest, zero
 * mocks — same posture as MockRuleServiceTest):
 * (1) a scenario persists its states/initial/scope declaration inside the
 * owning space with (space_id, name) uniqueness enforced by a plain-word
 * conflict, never a database 500;
 * (2) the definition shape is refused at the save-chain single point before
 * the row lands: states must be a non-empty JSON array of labels, the initial
 * state must be one of them, and every transition endpoint must be declared;
 * (3) stub rows wire to the machine through the same single point: an unknown
 * scenario name, a state outside the declared set, or state columns carried
 * without scenario membership are each refused with a human-readable reason;
 * (4) a legal scenario-bound stub persists required_state/new_state and the
 * snapshot view reads them back — the columns the traffic plane consumes in
 * land through this chain and nowhere else.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockScenarioServiceTest {

    @Autowired
    private MockScenarioService scenarioService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t361a" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace() {
        String key = "sc-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(newProject(), new MockSpaceInput(
                "Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    /** Definition with declared states only — initial defaults to the first state. */
    private MockScenarioInput scenarioInput(String name, String definitionJson) {
        return new MockScenarioInput(name, definitionJson);
    }

    private MockStubInput statefulStub(String name, String scenario, String required,
                                       String next, String responseJson) {
        return new MockStubInput(name, 100, "POST", "exact", "/orders/" + name,
                "[]", responseJson == null ? "{}" : responseJson,
                scenario, required, next, "manual", null, true);
    }

    @Test
    void createScenarioPersistsDeclarationWithDefaults() {
        long spaceId = newSpace();
        long id = scenarioService.create(spaceId,
                scenarioInput("order-flow", "{\"states\":[\"Started\",\"CREATED\",\"PAID\"]}"));

        MockScenarioInfo info = scenarioService.get(id);
        assertEquals(spaceId, info.spaceId().longValue());
        assertEquals("order-flow", info.name());
        assertNotNull(info.definitionJson());
        assertTrue(info.definitionJson().contains("CREATED"), "declaration must round-trip verbatim");
        // the (space,name) row exists exactly once — uniqueness column, not a shadow copy
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_mock_scenario WHERE id = ?", Integer.class, id);
        assertEquals(1, rows);
    }

    @Test
    void duplicateScenarioNameInSameSpaceIsRefusedInPlainWords() {
        long spaceId = newSpace();
        scenarioService.create(spaceId, scenarioInput("checkout", "{\"states\":[\"Started\"]}"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scenarioService.create(spaceId,
                        scenarioInput("checkout", "{\"states\":[\"Started\"]}")));
        assertTrue(ex.getMessage().contains("checkout"),
                "the refusal must name the occupied scenario: " + ex.getMessage());
    }

    @Test
    void illegalDefinitionShapesAreRefusedBeforeTheRowLands() {
        long spaceId = newSpace();

        // empty state set = a machine with no states cannot advance anywhere
        assertThrows(ServiceException.class, () -> scenarioService.create(spaceId,
                scenarioInput("empty", "{\"states\":[]}")));
        // initial outside the declared set
        assertThrows(ServiceException.class, () -> scenarioService.create(spaceId,
                scenarioInput("ghost-init", "{\"states\":[\"A\"],\"initial\":\"GONE\"}")));
        // transition endpoints outside the declared set
        assertThrows(ServiceException.class, () -> scenarioService.create(spaceId,
                scenarioInput("bad-edge",
                        "{\"states\":[\"A\",\"B\"],\"transitions\":[{\"from\":\"A\",\"to\":\"GONE\"}]}")));
        // payload that is not a JSON object at all
        assertThrows(ServiceException.class, () -> scenarioService.create(spaceId,
                scenarioInput("not-json", "[1,2]")));
        // scope outside the frozen spectrum (M-c: session default, role optional, global)
        assertThrows(ServiceException.class, () -> scenarioService.create(spaceId,
                scenarioInput("bad-scope", "{\"states\":[\"A\"],\"scope\":\"tenant\"}")));
    }

    @Test
    void stubBoundToUnknownScenarioIsRefused() {
        long spaceId = newSpace();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> stubService.create(spaceId, statefulStub("x1", "ghost-machine", null, null, null)));
        assertTrue(ex.getMessage().contains("ghost-machine"),
                "the refusal must name the unknown scenario: " + ex.getMessage());
    }

    @Test
    void stubStatesOutsideTheDeclaredSetAreRefused() {
        long spaceId = newSpace();
        scenarioService.create(spaceId,
                scenarioInput("order-flow", "{\"states\":[\"Started\",\"CREATED\"]}"));

        // required_state outside the machine
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                statefulStub("x2", "order-flow", "SHIPPED", null, null)));
        // new_state outside the machine
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                statefulStub("x3", "order-flow", "Started", "SHIPPED", null)));
        // state columns carried without scenario membership (red line: no orphan state)
        assertThrows(ServiceException.class, () -> stubService.create(spaceId,
                statefulStub("x4", null, "Started", null, null)));
    }

    /**
 * segment 2 seam on the core side: the polled snapshot must carry the space's
 * scenario declarations beside the rules, so the traffic plane can step
 * lanes without ever touching PostgreSQL. Publishing is the serving gate —
 * draft snapshots stay honest about the header but leak neither stubs nor
 * machines. Filing a machine must also move the content pin (the loader
 * re-parses on real drift; an invisible declaration change would silently
 * keep serving under the old machine set).
 */
    @Test
    void snapshotCarriesScenarioDeclarationsOnlyWhilePublished() {
        long projectId = newProject();
        String key = "sc-vm-" + UUID.randomUUID().toString().substring(0, 8);
        long spaceId = spaceService.create(projectId, new MockSpaceInput(
                "Machine Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
        scenarioService.create(spaceId,
                scenarioInput("order-flow", "{\"states\":[\"Started\",\"CREATED\"],\"initial\":\"Started\"}"));

        // draft: header honest, machines withheld
        assertEquals(0, spaceService.snapshot(key).scenarios().size(),
                "draft snapshot must not leak scenario declarations");

        // publish through the optimistic-locked transition (current row version)
        spaceService.transition(spaceId, 1, currentVersion(spaceId));
        var published = spaceService.snapshot(key);
        assertEquals(1, published.scenarios().size(),
                "published snapshot carries the machine declaration verbatim");
        assertEquals("order-flow", published.scenarios().get(0).name());
        assertTrue(published.scenarios().get(0).definitionJson().contains("CREATED"),
                "the declaration travels as saved — parsing stays in the shared kernel");

        // filing another machine moves the pin (loader must see the drift)
        String v1 = spaceService.snapshot(key).version();
        scenarioService.create(spaceId, scenarioInput("refund-flow", "{\"states\":[\"Open\",\"Done\"]}"));
        assertNotEquals(v1, spaceService.snapshot(key).version(),
                "a new machine is a content change — the version pin must move");
    }

    private int currentVersion(long spaceId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_mock_space WHERE id = ?", Integer.class, spaceId);
        return v == null ? 0 : v;
    }

    @Test
    void legalScenarioStubPersistsStateColumnsForTheSnapshot() {
        long spaceId = newSpace();
        scenarioService.create(spaceId,
                scenarioInput("order-flow", "{\"states\":[\"Started\",\"CREATED\",\"PAID\"]}"));

        long stubId = stubService.create(spaceId,
                statefulStub("x5", "order-flow", "CREATED", "PAID",
                        "{\"status\":200,\"body\":\"{\\\"ok\\\":true}\"}"));
        assertEquals("CREATED", stubService.get(stubId).requiredState());
        assertEquals("PAID", stubService.get(stubId).newState());
        assertEquals("order-flow", stubService.get(stubId).scenarioName());

        // a member rule without required_state fires in any state — both columns legal as NULL
        long anyId = stubService.create(spaceId, statefulStub("x6", "order-flow", null, null, null));
        assertNull(stubService.get(anyId).requiredState());
    }
}
