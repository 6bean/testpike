package com.sixbean.testpike.core.tool.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.controller.CaseInternalController;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionCompareRequest;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.CaseVersionCompareToolSeedInitializer;
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
 * Case version-compare built-in read tool seed test .
 * Same "actually dialed" posture as the flow-guide seed: the dialog
 * dispatcher POSTs the model input to the exec_spec url, so this pins the
 * registry row (name = FC routing index, published through the approval
 * chain, read semantics = no x-write) AND the POST consumption shape —
 * missing anchors or an unknown case must be refused in human words at the
 * door, matching the TC-ITR-07 honest-absence contract.
 */
@SpringBootTest(classes = CoreApplication.class)
class CaseVersionCompareToolSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTENT = "case.version-compare";

    @Autowired
    private CaseVersionCompareToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private CaseInternalController caseInternalController;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ToolSuiteSupport suite;

    @BeforeEach
    void openCompareFixture() {
        suite = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of());
    }

    @AfterEach
    void restoreCompareFactoryRows() {
        suite.restoreSeedFamily(seedInitializer::seedMissing);
    }

    @Test
    void seedsOnePublishedReadTool_realDialUrlAndNoWriteFlag() {
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
        JsonNode spec = read(tool.execSpecJson());
        assertEquals("http://localhost:8083/internal/cases/version-compare", spec.path("url").asText(),
                "read tools are actually dialed — the url must be the live POST endpoint");
        JsonNode schema = read(tool.inputSchemaJson());
        assertFalse(schema.path("x-write").asBoolean(false),
                INTENT + " is read-only: schema must not carry x-write=true");
        // A compare without both ends is meaningless: caseId + toVersion are
        // mandatory anchors (baseline stays optional = compare against latest).
        String required = schema.path("required").toString();
        assertTrue(required.contains("caseId") && required.contains("toVersion"),
                "required anchors must pin caseId + toVersion");

        assertEquals(1, suite.toolAuditCount("approve", suite.idsWithIntent(INTENT)));
    }

    @Test
    void postEndpointServesTheDialShape_refusingMissingAnchorsAndUnknownCase() {
        seedInitializer.seedMissing();
        // Unknown case: the shared diff door answers 40401 in human words
        // (the dialog turns a refusal into an honest absence, never a card).
        assertThrows(ServiceException.class,
                () -> caseInternalController.versionComparePost(
                        new CaseVersionCompareRequest(987654321L, 1, 2)));
        // Missing target version anchor: refused at validation, no door call.
        assertThrows(ServiceException.class,
                () -> caseInternalController.versionComparePost(
                        new CaseVersionCompareRequest(12L, 3, null)));

        // The refusal wording is the SHARED door's established 404 contract
        // (the missing-case sentence the diff/rollback doors give), so the
        // card asserts that honest refusal surfaces intact — never a
        // re-wrapped or emptied answer.
        ServiceException refused = assertThrows(ServiceException.class,
                () -> caseInternalController.versionComparePost(
                        new CaseVersionCompareRequest(987654321L, 1, 2)));
        assertNotNull(refused.getMessage(), "refusal must carry the human-readable reason");
        assertTrue(refused.getMessage().contains("用例不存在"),
                "refusal must mirror the shared door wording, got: " + refused.getMessage());
    }

    @Test
    void secondRunIsIdempotent_missingRowGetRepaired() {
        seedInitializer.seedMissing();
        assertEquals(0, seedInitializer.seedMissing(), "complete list = second run creates nothing");

        SharedDbFixtureSweep.deleteTools(jdbcTemplate, suite.idsWithIntent(INTENT));
        assertEquals(1, seedInitializer.seedMissing(), "missing entry is re-seeded only");
        assertEquals(0, seedInitializer.seedMissing());
    }

    @Test
    void occupiedNameWithoutIntentIsSkipped_notHalfRegistered() {
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
