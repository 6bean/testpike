package com.sixbean.testpike.core.tool.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.CaseWriteToolSeedInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Case-write built-in tool seed test (dialog draft-card chain, tool registry
 * side). The emitter in ai-service matches these tools by their literal names
 * {@code case.create} / {@code case.update}, so the name is the routing
 * contract and this test pins it against drift. Idempotency anchor is the
 * exec_spec intent (renaming a tool must not re-seed a duplicate).
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseWriteToolSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> INTENTS =
            List.of("case.create", "case.update", "case.rollback");

    @Autowired
    private CaseWriteToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture scaffolding + teardown tracker, single-sourced in ToolSuiteSupport (R40). */
    private ToolSuiteSupport suite;

    @BeforeEach
    void openWriteSeedFixture() {
        suite = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of());
    }

    @AfterEach
    void restoreWriteSeedFactoryRows() {
        suite.restoreSeedFamily(seedInitializer::seedMissing);
    }

    @Test
    void seedsThreePublishedWriteTools_withSchemaAnchorsAndAuditTrail() {
        // R37 fixture scoping: temporarily remove the shipped intent rows via
        // the FK-aware sweep so the seed run re-creates them through the real
        // approval chain instead of the suite wiping the shared tables.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate,
                suite.idsWithIntent("case.create", "case.update", "case.rollback"));
        int created = seedInitializer.seedMissing();
        assertEquals(3, created);

        for (String intent : INTENTS) {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_tool WHERE exec_spec ->> 'intent' = ?", Long.class, intent);
            assertNotNull(id, intent + " must be registered as a tool");
            ToolInfo tool = toolService.get(id);
            assertEquals(intent, tool.name(), "name literal = FC routing index, must not drift");
            assertEquals(1, tool.execKind(), intent + " uses the HTTP exec form");
            assertEquals(3, tool.status(), intent + " must finish the approval chain (published)");
            assertNotNull(tool.inputSchemaJson(), intent + " carries an input schema");
            // Write semantics: the dispatcher refuses token-less execution and the
            // dialog chain turns calls into confirmable draft cards instead.
            JsonNode schema = read(tool.inputSchemaJson());
            assertTrue(schema.path("x-write").isBoolean() && schema.path("x-write").asBoolean(),
                    intent + " schema root must carry x-write=true");
            List<String> required = textList(schema.path("required"));
            if ("case.update".equals(intent)) {
                assertTrue(required.containsAll(List.of("caseId", "baseVersion")),
                        "edit anchor pair must be required");
            } else if ("case.rollback".equals(intent)) {
                assertTrue(required.containsAll(List.of("caseId", "targetVersion", "baseVersion")),
                        "rollback must declare the case, the target version and the lock anchor");
            } else {
                assertTrue(required.contains("title"), "create must declare required title");
            }
            // Registry-driven card routing : the
            // emitter finds the draft kind through this token, never through
            // a hard-coded tool name. Rollback lands on its own strategy so
            // its token differs from the create/edit family.
            JsonNode spec = read(tool.execSpecJson());
            assertEquals("case.rollback".equals(intent) ? "CASE_ROLLBACK" : "CASE_WRITE",
                    spec.path("draftObjectType").asText(),
                    intent + " must carry the draft routing token");
            assertEquals(intent, spec.path("intent").asText(),
                    intent + " idempotency anchor must match its name");
        }
        // create → submit → approve ran through ToolService, not bypassed:
        // every published tool left an approve audit row. Scoped to this run's
        // rows (object_id = text of the current intent rows' ids) so audit
        // rows from other batches on the shared dev database cannot interfere.
        assertEquals(3, suite.toolAuditCount("approve",
                suite.idsWithIntent("case.create", "case.update", "case.rollback")));
    }

    @Test
    void secondRunIsIdempotent_missingRowsGetRepaired() {
        seedInitializer.seedMissing();
        assertEquals(0, seedInitializer.seedMissing(), "complete list = second run creates nothing");

        // A hand-removed row (takedown/deletion) is repaired on the next run.
        // Removal goes through the FK-aware sweep so children hang no loose ends.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate, suite.idsWithIntent("case.create"));
        assertEquals(1, seedInitializer.seedMissing(), "missing entries are re-seeded only");
        assertEquals(0, seedInitializer.seedMissing());
    }

    @Test
    void occupiedNameWithoutIntentIsSkipped_notHalfRegistered() {
        // A hand-made system tool squatting the contract name must not collide
        // into a DuplicateKey crash nor double-register (skip + repair the rest).
        // The shipped intent rows are removed first so the squatted name is free.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate,
                suite.idsWithIntent("case.create", "case.update", "case.rollback"));
        Long squatId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_tool (project_id, name, type, status) "
                        + "VALUES (NULL, 'case.create', 1, 1) RETURNING id", Long.class);
        suite.seedSquats().add(squatId);
        int created = seedInitializer.seedMissing();
        assertEquals(2, created,
                "only case.update and case.rollback register while the name is squatted");
        Long squatStillDraft = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = 'case.create' AND status = 1", Long.class);
        assertEquals(1, squatStillDraft, "the squatted row stays untouched (skip, never overwrite)");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = 'case.create'", Long.class),
                "no second row under the same contract name");
        assertEquals(0, seedInitializer.seedMissing(), "stable skip across restarts");
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("seeded schema must be valid JSON", e);
        }
    }

    private static List<String> textList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        if (arrayNode.isArray()) {
            arrayNode.forEach(node -> values.add(node.asText()));
        }
        return values;
    }
}
