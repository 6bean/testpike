package com.sixbean.testpike.core.tool.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.RequirementWriteToolSeedInitializer;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
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
 * Requirement-write built-in tool seed test .
 * The dialog emitter routes cards through the registry token, not name
 * literals, so the exec_spec {@code draftObjectType} marker on these two
 * rows is the contract this test pins against drift — the landing strategy
 * answers only to REQUIREMENT_WRITE frames whose tool row is published.
 * <p>No TRUNCATE here (baseline-residency rule R37): the seeded rows ARE
 * factory data on the shared dev database, so the test asserts the runner's
 * own idempotent outcome instead of clearing the table.
 */
@SpringBootTest(classes = CoreApplication.class)
class RequirementWriteToolSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> INTENTS = List.of("requirement.create", "requirement.update");
    private static final String DRAFT_GATE_KEY = "ai.chat.requirement-draft.enabled";

    @Autowired
    private RequirementWriteToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureFactorySeedPair() {
        // @SpringBootTest never runs ApplicationRunner beans, so the startup hook that
        // guarantees the pair on a real boot does not fire here; on a freshly provisioned
        // chain slot the tp_tool rows would be absent. Invoke the same official idempotent
        // channel the hook uses (R37: single seed channel), then assertions below pin the
        // factory shape and re-running stays a no-op.
        seedInitializer.seedMissing();
    }

    @Test
    void bothContractToolsExistInPublishedFormWithTheRegistryToken() {
        for (String intent : INTENTS) {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_tool WHERE exec_spec ->> 'intent' = ?", Long.class, intent);
            assertNotNull(id, intent + " must be registered as a tool");
            ToolInfo tool = toolService.get(id);
            assertEquals(intent, tool.name(), "name literal = the FC assembly index, must not drift");
            assertEquals(1, tool.execKind(), intent + " uses the HTTP exec form (placeholder url only)");
            assertEquals(3, tool.status(), intent + " must finish the approval chain (published)");

            JsonNode spec = read(tool.execSpecJson());
            assertEquals("REQUIREMENT_WRITE", spec.path("draftObjectType").asText(),
                    intent + " must carry the registry routing token the emitter reads");

            JsonNode schema = read(tool.inputSchemaJson());
            assertTrue(schema.path("x-write").isBoolean() && schema.path("x-write").asBoolean(),
                    intent + " schema root must carry x-write=true — the dispatcher refuses it token-less");
            List<String> required = textList(schema.path("required"));
            if ("requirement.update".equals(intent)) {
                assertTrue(required.containsAll(List.of("requirementId", "baseVersion")),
                        "edit anchor pair must be required");
            } else {
                assertTrue(required.contains("title"), "create must declare required title");
            }
        }
    }

    @Test
    void reRunningTheSeedCreatesNothing() {
        // Idempotency anchor = exec_spec intent: a complete pair re-runs clean.
        assertEquals(0, seedInitializer.seedMissing());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE exec_spec ->> 'intent' IN "
                        + "('requirement.create','requirement.update')", Integer.class);
        assertEquals(2, rows, "re-seed must never duplicate the pair");
    }

    @Test
    void requirementDraftSwitchKeyShipsEnabledByFactoryDefault() {
        // R32: AI-driven draft switches ship ON — zero-config out of the box.
        String value = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = "
                        + "'ai.chat.requirement-draft.enabled'", String.class);
        assertEquals("true", value, "factory default must be enabled");
    }

    @Test
    void draftGateRowIsReplenishedByTheStartupHookAfterAWash() {
        // A wiped factory gate row silently kills draft cards (the emitter
        // treats a missing/blank key as closed). The startup hook must put
        // it back, once and idempotently, with the factory literals.
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", DRAFT_GATE_KEY);
        try {
            assertEquals(1, seedInitializer.ensureDraftSwitch(),
                    "a missing factory gate row must be replenished exactly once");
            java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT config_value, group_name, value_type, encrypt_flag, description"
                            + " FROM tp_sys_config WHERE config_key = ?", DRAFT_GATE_KEY);
            assertEquals("true", row.get("config_value"),
                    "replenished row ships enabled — the R32 factory default");
            assertEquals("系统", row.get("group_name"));
            assertEquals("bool", row.get("value_type"));
            assertEquals(0, ((Number) row.get("encrypt_flag")).intValue());
            assertTrue(String.valueOf(row.get("description")).startsWith("需求点草案开关"),
                    "description must carry the migration's factory wording, not a placeholder");
            assertEquals(0, seedInitializer.ensureDraftSwitch(),
                    "re-running the hook must add nothing");
        } finally {
            seedInitializer.ensureDraftSwitch(); // leave the gate row resident for other suites
        }
    }

    @Test
    void aHumanOptOutGateValueIsNeverOverwrittenByTheHook() {
        // Closing the switch is an explicit human choice with an audit trail;
        // the hook only fills a missing row and never rewrites a present one.
        seedInitializer.ensureDraftSwitch();
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'false' WHERE config_key = ?",
                DRAFT_GATE_KEY);
        try {
            assertEquals(0, seedInitializer.ensureDraftSwitch());
            assertEquals("false", jdbcTemplate.queryForObject(
                    "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                    String.class, DRAFT_GATE_KEY),
                    "a human-set 'false' must survive the replenish hook");
        } finally {
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'true' WHERE config_key = ?",
                    DRAFT_GATE_KEY);
        }
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("seeded JSON unreadable: " + json, e);
        }
    }

    private static List<String> textList(JsonNode arrayNode) {
        List<String> out = new ArrayList<>();
        if (arrayNode.isArray()) {
            arrayNode.forEach(item -> out.add(item.asText()));
        }
        return out;
    }
}
