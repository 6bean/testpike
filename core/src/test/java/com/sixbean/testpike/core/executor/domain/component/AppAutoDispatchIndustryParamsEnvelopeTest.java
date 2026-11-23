package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.plugin.domain.service.PluginService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Dispatch-envelope stamping of applied industry-plugin parameters (segment P5d-1,
 * producer face of the industry-package linkage).
 * <p>Verified contracts:
 * ① an applied plugin's param snapshot lands in the envelope under {@code industryParams}
 * with real values (factory sample pos-retail: pos_terminal_id/currency);
 * ② a project without applied plugins keeps the key absent (empty-does-not-emit
 * discipline, same shape as the optional stamps already on the envelope);
 * ③ on a shared key the earlier applied plugin wins and the later value is skipped,
 * never silently overriding (merge order = applied_at ascending);
 * ④ a disabled plugin's snapshot is not stamped (disabled = no new execution effect);
 * ⑤ the serialized envelope carries no leftover credential placeholder token.
 * <p>Real dev DB via SpringBootTest (no mocks); fixture rows created per test and
 * cascade-cleaned by prefix, no TRUNCATE on factory baseline tables.
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class AppAutoDispatchIndustryParamsEnvelopeTest {

    @Autowired
    private AppAutoDispatchSupport appAutoDispatchSupport;

    @Autowired
    private PluginService pluginService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "idp" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "IndustryParamsFixture-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        // buildEnvelope asserts an online capable executor (K8 health gate): one
        // MOBILEWRIGHT/ANDROID/EMULATOR fixture keeps the dispatch face reachable.
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'idp-ex-%'");
        executorService.register(new ExecutorRegisterInput(
                "idp-ex-" + System.nanoTime(), null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", List.of("MOBILEWRIGHT"),
                        "platforms", List.of("ANDROID"),
                        "deviceKinds", List.of("EMULATOR"))));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_project_plugin WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project_skill WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_plugin WHERE name LIKE 'idp-%'");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'idp-ex-%'");
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private List<Map<String, Object>> launchSteps() {
        return List.of(Map.of("action", "app.launch", "target", Map.of("pkg", "com.demo")));
    }

    private Map<String, Object> buildEnvelope() {
        return appAutoDispatchSupport.buildEnvelope(projectId, null, null, null, launchSteps());
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliedPluginParametersLandInEnvelope() throws Exception {
        Long pluginId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_plugin WHERE name = 'pos-retail' AND version = '1.0.0'",
                Long.class);
        assertNotNull(pluginId, "factory pos-retail plugin must be on the baseline");
        pluginService.apply(null, projectId, pluginId, false);

        Map<String, Object> envelope = buildEnvelope();
        Map<String, Object> stamped = (Map<String, Object>) envelope.get("industryParams");
        assertNotNull(stamped, "industryParams key must be stamped for an applied plugin");
        assertEquals("T01", stamped.get("pos_terminal_id"));
        assertEquals("TWD", stamped.get("currency"));
        assertFalse(objectMapper.writeValueAsString(envelope).contains("{{CRED:"),
                "serialized envelope must not carry a leftover credential placeholder token");
    }

    @Test
    void noAppliedPluginKeepsKeyAbsent() {
        assertFalse(buildEnvelope().containsKey("industryParams"),
                "empty applied-plugin set must not emit the industryParams key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void earlierAppliedPluginWinsSharedKey() {
        insertFixturePlugin("idp-first", true, "{\"param_template\": {\"idpSharedKey\": \"FIRST\", \"firstOnly\": \"1\"}}");
        insertFixturePlugin("idp-second", true, "{\"param_template\": {\"idpSharedKey\": \"SECOND\", \"secondOnly\": \"2\"}}");
        long firstId = pluginIdOf("idp-first");
        long secondId = pluginIdOf("idp-second");
        // Explicit applied_at values keep the merge-order assertion deterministic:
        // idp-first applied earlier owns the shared key.
        jdbcTemplate.update("INSERT INTO tp_project_plugin (project_id, plugin_id, param_snapshot, applied_at) "
                        + "VALUES (?, ?, ?::jsonb, now() - interval '2 minutes')",
                projectId, firstId, "{\"idpSharedKey\": \"FIRST\", \"firstOnly\": \"1\"}");
        jdbcTemplate.update("INSERT INTO tp_project_plugin (project_id, plugin_id, param_snapshot, applied_at) "
                        + "VALUES (?, ?, ?::jsonb, now())",
                projectId, secondId, "{\"idpSharedKey\": \"SECOND\", \"secondOnly\": \"2\"}");

        Map<String, Object> stamped = (Map<String, Object>) buildEnvelope().get("industryParams");
        assertNotNull(stamped);
        assertEquals("FIRST", stamped.get("idpSharedKey"),
                "the earlier applied plugin must win a shared key");
        assertEquals("1", stamped.get("firstOnly"));
        assertEquals("2", stamped.get("secondOnly"), "non-conflicting later keys still merge");
    }

    @Test
    void disabledPluginParametersAreNotStamped() {
        insertFixturePlugin("idp-off", false, "{\"param_template\": {\"idpOffKey\": \"OFF\"}}");
        long offId = pluginIdOf("idp-off");
        jdbcTemplate.update("INSERT INTO tp_project_plugin (project_id, plugin_id, param_snapshot) "
                        + "VALUES (?, ?, ?::jsonb)",
                projectId, offId, "{\"idpOffKey\": \"OFF\"}");

        assertFalse(buildEnvelope().containsKey("industryParams"),
                "a disabled plugin's snapshot must not be stamped into the envelope");
    }

    private void insertFixturePlugin(String name, boolean enabled, String manifestJson) {
        jdbcTemplate.update("INSERT INTO tp_plugin (name, industry, version, manifest, enabled, source) "
                        + "VALUES (?, 'POS', '1.0.0', ?::jsonb, ?, 2)",
                name, manifestJson, enabled);
    }

    private long pluginIdOf(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM tp_plugin WHERE name = ?", Long.class, name);
    }
}
