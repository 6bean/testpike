package com.sixbean.testpike.core.tool.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.controller.CaseInternalController;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideInfo;
import com.sixbean.testpike.core.cases.api.dto.FlowGuideRequest;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.CaseFlowGuideToolSeedInitializer;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case flow-guide built-in tool seed test (AO-9 stage 2). Unlike the 26
 * placeholder-shaped write tools, this is the repository's first REAL-dialed
 * HTTP tool: the dialog dispatcher POSTs the model's raw input JSON to the
 * exec_spec url, so this test pins both the registry row (name = FC routing
 * index, published via the approval chain, read semantics = no x-write) and
 * the POST consumption shape the dialer actually hits.
 * <p>Idempotency anchor = exec_spec intent; the url literal is pinned against
 * drift because a placeholder url would silently reproduce the old "never
 * dialed" shape and defeat the whole point of this tool.
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseFlowGuideToolSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTENT = "case.flow-guide";

    @Autowired
    private CaseFlowGuideToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private CaseInternalController caseInternalController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture scaffolding + teardown tracker, single-sourced in ToolSuiteSupport (R40). */
    private ToolSuiteSupport suite;

    @BeforeEach
    void openFlowGuideFixture() {
        suite = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of());
    }

    @AfterEach
    void restoreFlowGuideFactoryRows() {
        suite.restoreSeedFamily(seedInitializer::seedMissing);
    }

    @Test
    void seedsOnePublishedReadTool_realDialUrlAndNoWriteFlag() {
        // R37 fixture scoping: temporarily remove the shipped intent row via
        // the FK-aware sweep so the seed run re-creates it through the real
        // approval chain instead of the suite wiping the shared tables.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate, suite.idsWithIntent(INTENT));
        int created = seedInitializer.seedMissing();
        assertEquals(1, created);

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_tool WHERE exec_spec ->> 'intent' = ?", Long.class, INTENT);
        assertNotNull(id, INTENT + " must be registered as a tool");
        ToolInfo tool = toolService.get(id);
        assertEquals(INTENT, tool.name(), "name literal = FC routing index, must not drift");
        assertEquals(1, tool.execKind(), "uses the HTTP exec form");
        assertEquals(3, tool.status(), "must finish the approval chain (published)");
        // The dial contract: exec_kind=1 execution POSTs the model input to this
        // url — it must point at the real POST endpoint, never a placeholder.
        JsonNode spec = read(tool.execSpecJson());
        assertEquals("http://localhost:8083/internal/cases/flow-guide", spec.path("url").asText(),
                "read tools are actually dialed — the url must be the live POST endpoint");
        JsonNode schema = read(tool.inputSchemaJson());
        // Read semantics: the write gate must not fire, otherwise the dialog
        // would demand a confirm token for a pure query.
        assertFalse(schema.path("x-write").asBoolean(false),
                INTENT + " is read-only: schema must not carry x-write=true");
        // caseId optional: absent = whole-graph question.
        assertFalse(schema.path("required").toString().contains("caseId"),
                "caseId must stay optional for graph-level questions");

        // create -> submit -> approve ran through ToolService, not bypassed.
        // Audit count scoped to the run's own row (object_id = text of the
        // current intent row's id) per the shared-dev-database discipline.
        assertEquals(1, suite.toolAuditCount("approve", suite.idsWithIntent(INTENT)));
    }

    @Test
    void postEndpointServesTheDialShape_bodyCaseIdAndEmptyBody() {
        seedInitializer.seedMissing();
        // Empty body = whole status vocabulary map (9 states, single source).
        Response<FlowGuideInfo> graph = caseInternalController.flowGuidePost(new FlowGuideRequest(null));
        assertEquals("0", graph.getCode());
        assertEquals(9, graph.getData().states().size(),
                "graph answer must mirror CaseStatusMachine vocabulary");
        assertTrue(graph.getData().actionsForCurrent().isEmpty(),
                "no live case = no per-case actions");

        // Unknown case id: human-name the failure instead of an empty answer
        // (same contract as the GET face from stage 1).
        assertThrows(ServiceException.class,
                () -> caseInternalController.flowGuidePost(new FlowGuideRequest(987654321L)));
    }

    @Test
    void secondRunIsIdempotent_missingRowGetRepaired() {
        seedInitializer.seedMissing();
        assertEquals(0, seedInitializer.seedMissing(), "complete list = second run creates nothing");

        // Removal goes through the FK-aware sweep (children first), never a raw
        // DELETE that could strand tp_tool_version/tp_project_tool rows.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate, suite.idsWithIntent(INTENT));
        assertEquals(1, seedInitializer.seedMissing(), "missing entry is re-seeded only");
        assertEquals(0, seedInitializer.seedMissing());
    }

    @Test
    void occupiedNameWithoutIntentIsSkipped_notHalfRegistered() {
        // The shipped intent row is removed first so the contract name is free
        // for the foreign squatting row this scenario needs.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate, suite.idsWithIntent(INTENT));
        Long squatId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tool (project_id, name, type, status) "
                        + "VALUES (NULL, ?, 2, 1) RETURNING id", Long.class, INTENT);
        suite.seedSquats().add(squatId);
        int created = seedInitializer.seedMissing();
        assertEquals(0, created, "a foreign row squatting the contract name is skipped, never crashed");
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE exec_spec ->> 'intent' = ?", Long.class, INTENT);
        assertEquals(0, rows, "skipped seed leaves no half-registered row behind");
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("stored json must parse: " + e.getMessage(), e);
        }
    }
}
