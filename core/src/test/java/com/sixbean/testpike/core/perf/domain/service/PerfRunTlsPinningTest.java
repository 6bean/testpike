package com.sixbean.testpike.core.perf.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.component.PerfRunDispatchSupport;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-10 TLS three-tier pinning on the perf chain .
 * <p>Gap this test anchors: perf launch dispatched pressure runs with zero TLS
 * awareness — the environment profile's tls section (strict / custom_ca /
 * insecure, the W1a single-source model) never reached the pinned snapshot nor
 * the claim envelope, so a custom-CA or self-signed (insecure) target always
 * failed the handshake under engine defaults while the report claimed a plain
 * environment. The fix follows the GA-5 targetVersion discipline: the launch
 * pins the effective environment's TLS tier into the snapshot (mode label only
 * — credential material never enters a persisted snapshot), and the claim
 * assembly delivers the resolved {@code tlsPolicy} (with decrypted PEM
 * material) alongside the perf context for the executor to inject per engine.
 * <p>Red anchors: a custom_ca environment launch pins "custom_ca" in the
 * snapshot; the claim context carries tlsPolicy.mode plus the CA material; a
 * GB-1 override launch pins the overridden environment's tier (never the
 * scenario default's); an environment without a tls section pins strict
 * (fail-safe default, never silently relaxed); a run row whose snapshot
 * predates the key still assembles cleanly (legacy claim zero breakage).
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture
 * follows PerfRunTargetVersionPinningTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunTlsPinningTest {

    private static final String TEST_CA_PEM =
            "-----BEGIN CERTIFICATE-----\nTEST-CA-MATERIAL\n-----END CERTIFICATE-----";

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfRunDispatchSupport perfRunDispatchSupport;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;
    private long envAId;
    private long envBId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long uniq = System.nanoTime() % 1_000_000_000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "TLS档位钉注测试项目_" + uniq, "PFTLSP" + uniq);
        projectId = lastId("tp_project");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '测试-自定义CA', 2, 'https://perf-ca.local/api')", projectId);
        envAId = lastId("tp_project_env");
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, '开发-默认校验', 1, 'https://perf-strict.local/api')", projectId);
        envBId = lastId("tp_project_env");
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreSeed("perf.production-enabled", "false");
        restoreSeed("perf.blocked-target-segments", "");
    }

    private void cleanupRows() {
        for (String prefix : List.of("PFTLSP")) {
            jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_approval WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                    + "(SELECT id FROM tp_project WHERE code LIKE '" + prefix + "%')");
            jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE '" + prefix + "%'");
        }
    }

    private long lastId(String table) {
        return jdbcTemplate.queryForObject("SELECT max(id) FROM " + table, Long.class);
    }

    private void restoreSeed(String key, String value) {
        Long id = jdbcTemplate.query("SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(null, id);
        }
        configService.create(null, new ConfigInput(key, value, "系统", "string", 0,
                "tls tier pin seed managed by perf tls-pinning test"));
    }

    /** Write a raw tls section onto the environment profile (plaintext PEM =
     *  the historical no-ENC pass-through the resolver tolerates by design). */
    private void setEnvTlsSection(long envId, String mode) {
        String configJson = mode == null ? "{}"
                : "{\"tls\":{\"mode\":\"" + mode + "\""
                        + (mode.equals("custom_ca")
                                ? ",\"ca_pem\":\"" + TEST_CA_PEM.replace("\n", "\\n") + "\"" : "")
                        + "}}";
        jdbcTemplate.update("UPDATE tp_project_env SET config_json = CAST(? AS jsonb) WHERE id = ?",
                configJson, envId);
    }

    private void addWhitelist(long envId) {
        jdbcTemplate.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency, "
                        + "allowed_modes) VALUES (?, ?, 100, CAST(? AS jsonb))",
                projectId, envId, "[\"SHADOW_TABLE\"]");
    }

    private long newScenario(long envId) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                "TLS档位场景", envId, "CONSTANT_RPS",
                Map.of("targetRps", 30, "durationSeconds", 60),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "https://perf-ca.local/api/orders")),
                null, null));
    }

    private JsonNode snapshotOf(long runId) throws Exception {
        String raw = (String) perfRunService.view(projectId, runId).get("pressure_snapshot");
        return objectMapper.readTree(raw);
    }

    /** claim context of the single dispatch task created by this launch. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> claimContextOf(long runId) {
        String envelope = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE project_id = ? "
                        + "AND schema_version = 'perf-v1'", String.class, projectId);
        Map<String, Object> perf = perfRunDispatchSupport.assembleForClaim(projectId, envelope);
        assertNotNull(perf, "launch 产物信封必须可被 claim 装配（脏单=链断）");
        assertEquals(runId, ((Number) perf.get("runId")).longValue());
        return perf;
    }

    /** custom_ca spectrum: the launch pins the effective environment's tier and
     *  the claim hands the resolved policy (mode + CA material) to the executor. */
    @Test
    void customCaEnvironmentPinsTierAndDeliversCaMaterial() throws Exception {
        setEnvTlsSection(envAId, "custom_ca");
        addWhitelist(envAId);
        long scenario = newScenario(envAId);

        long run = perfRunService.launch(projectId, scenario, operatorId);

        assertEquals("custom_ca", snapshotOf(run).path("tlsMode").asText(),
                "快照必须钉住生效环境的 TLS 档位: " + snapshotOf(run));
        Map<String, Object> perf = claimContextOf(run);
        Map<String, Object> tlsPolicy = (Map<String, Object>) perf.get("tlsPolicy");
        assertNotNull(tlsPolicy, "claim 上下文必须携 tlsPolicy（执行器注入三档的输入）");
        assertEquals("custom_ca", tlsPolicy.get("mode"));
        assertTrue(String.valueOf(tlsPolicy.get("caPem")).contains("TEST-CA-MATERIAL"),
                "custom_ca 决议必须带 CA 材料下发");
    }

    /** Fail-safe default: an environment with no tls section pins strict
     *  (never a silent relaxation), and the policy carries no material keys. */
    @Test
    @SuppressWarnings("unchecked")
    void environmentWithoutTlsSectionPinsStrictDefault() throws Exception {
        addWhitelist(envBId);
        long scenario = newScenario(envBId);

        long run = perfRunService.launch(projectId, scenario, operatorId);

        assertEquals("strict", snapshotOf(run).path("tlsMode").asText(),
                "无 tls 段=失败安全钉 strict");
        Map<String, Object> tlsPolicy = (Map<String, Object>) claimContextOf(run).get("tlsPolicy");
        assertNotNull(tlsPolicy);
        assertEquals("strict", tlsPolicy.get("mode"));
        assertFalse(tlsPolicy.containsKey("caPem"), "纯校验档省略材料键（信封兼容）");
    }

    /** GB-1 override spectrum: the pinned tier describes the environment that
     *  was actually pressed (override B=strict), never the scenario default A. */
    @Test
    void overrideEnvironmentPinsItsOwnTierNotTheScenarioDefault() throws Exception {
        setEnvTlsSection(envAId, "custom_ca");
        addWhitelist(envBId);
        long scenario = newScenario(envAId);

        long run = perfRunService.launch(projectId, scenario, envBId, operatorId);

        assertEquals("strict", snapshotOf(run).path("tlsMode").asText(),
                "覆盖选环境B（无段=strict）必须钉B档位，非默认A的custom_ca");
    }

    /** Legacy zero breakage: a run row whose snapshot predates the tlsMode key
 * still assembles — the claim resolves live from the environment profile
 * (same discipline as targetVersion absence: honest value, no crash). */
    @Test
    @SuppressWarnings("unchecked")
    void legacySnapshotWithoutTierStillAssemblesPolicy() {
        setEnvTlsSection(envAId, "custom_ca");
        addWhitelist(envAId);
        long scenario = newScenario(envAId);
        long run = perfRunService.launch(projectId, scenario, operatorId);
        // Simulate a row: strip the pinned key from the stored snapshot.
        jdbcTemplate.update("UPDATE tp_perf_run SET pressure_snapshot = "
                + "pressure_snapshot - 'tlsMode' WHERE id = ?", run);

        Map<String, Object> tlsPolicy = (Map<String, Object>) claimContextOf(run).get("tlsPolicy");

        assertNotNull(tlsPolicy, "历史快照缺键不得炸 claim 装配");
        assertEquals("custom_ca", tlsPolicy.get("mode"),
                "claim 按生效环境档案实时决议（快照只留痕不供材料）");
    }
}
