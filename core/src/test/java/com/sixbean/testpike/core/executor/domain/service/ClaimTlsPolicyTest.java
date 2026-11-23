package com.sixbean.testpike.core.executor.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.TlsMode;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ClaimTlsPolicyTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";
    private static final String CA_PEM = "-----BEGIN CERTIFICATE-----\nINTERNAL-CA-TEST-MATERIAL\n-----END CERTIFICATE-----\n";
    private static final String CLIENT_CERT_PEM = "-----BEGIN CERTIFICATE-----\nCLIENT-CERT-MATERIAL\n-----END CERTIFICATE-----\n";
    private static final String CLIENT_KEY_PEM = "-----BEGIN PRIVATE KEY-----\nCLIENT-KEY-MATERIAL\n-----END PRIVATE KEY-----\n";

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ConfigCipher configCipher;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Fixture-scoped teardown : the suite's projects carry the fixed
    // through the shared FK-tree sweep (tp_exec_step_snapshot cascades off
    // tp_exec_task). Executors drop by the 'ex-tls-%' name family. Blanket TRUNCATE
    // of these shared tables would wipe concurrent suites' rows on the dev database.
    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                jdbcTemplate.queryForList(
                        "SELECT id FROM tp_project WHERE name LIKE 'TLS下发项目%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-tls-%'");
    }

    private long createProject() {
        return projectService.create(1L, new ProjectCreateInput("TLS下发项目",
                "TLSC" + System.nanoTime() % 100000, "zh-CN", "P1", null));
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
    }

    
    private void setEnvConfig(long envId, Map<String, Object> config) {
        try {
            jdbcTemplate.update("UPDATE tp_project_env SET config_json = ?::jsonb WHERE id = ?",
                    objectMapper.writeValueAsString(config), envId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> tlsSection(Map<String, Object> fields) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tls", fields);
        return config;
    }

    private ClaimTaskView claimTask(long projectId, long envId, String exName) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, "TLS下发用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, java.util.List.of()));
        execTaskService.create(new ExecTaskCreateInput(projectId, info.id(), "v2", ACTION_JSON, envId));
        long exId = executorService.register(new ExecutorRegisterInput(
                exName, null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "执行器必须领取到任务");
        return view;
    }

    @Test
    void claim_无tls段_失败安全strict兜底() {
        long pid = createProject();
        long envId = createEnv(pid);

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-plain");

        assertNotNull(view.tlsPolicy(), "tlsPolicy 键必须在位（尾追契约稳定，无档案也不缺键）");
        assertEquals(TlsMode.STRICT, view.tlsPolicy().mode(), "无 tls 段=失败安全 strict");
        assertNull(view.tlsPolicy().caPem());
        assertFalse(view.tlsPolicy().ignoreVerify(), "缺省绝不静默放宽为 insecure");
    }

    @Test
    void claim_insecure档_原样透传含材料省略() {
        long pid = createProject();
        long envId = createEnv(pid);
        setEnvConfig(envId, tlsSection(Map.of("mode", "insecure", "reason", "内网自签联调")));

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-insec");

        assertEquals(TlsMode.INSECURE, view.tlsPolicy().mode(), "insecure 原样透传（段闸在保存期不在解析期）");
        assertTrue(view.tlsPolicy().ignoreVerify());
        assertNull(view.tlsPolicy().caPem(), "无材料=不下发材料字段值");
    }

    @Test
    void claim_customCa密文_解密明文下发且库内保持密文() {
        Assumptions.assumeTrue(configCipher.isEnabled(), "ENC: 解密钉需 CONFIG_AES_KEY（.env 注入）");
        long pid = createProject();
        long envId = createEnv(pid);
        setEnvConfig(envId, tlsSection(Map.of("mode", "custom_ca",
                "ca_pem", "ENC:" + configCipher.encrypt(CA_PEM))));

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-ca");

        assertEquals(TlsMode.CUSTOM_CA, view.tlsPolicy().mode());
        assertEquals(CA_PEM, view.tlsPolicy().caPem(), "下发侧=解密明文（执行器禁二次解密，单源铁律）");
        String stored = jdbcTemplate.queryForObject(
                "SELECT config_json::text FROM tp_project_env WHERE id = ?", String.class, envId);
        assertTrue(stored.contains("ENC:"), "DB 侧保持密文形态");
        assertFalse(stored.contains("INTERNAL-CA-TEST-MATERIAL"), "库内零明文（红线 4）");
    }

    @Test
    void claim_customCa缺CA_降级strict_单源决议生效() {
        long pid = createProject();
        long envId = createEnv(pid);
        setEnvConfig(envId, tlsSection(Map.of("mode", "custom_ca")));

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-nodeg");

        assertEquals(TlsMode.STRICT, view.tlsPolicy().mode(),
                "配了 custom_ca 却没 CA 材料=降级 strict（校验不能静默失效，common 单源决议）");
    }

    @Test
    void claim_半截mTLS凭据_整体丢弃不冒充双向() {
        Assumptions.assumeTrue(configCipher.isEnabled(), "ENC: 解密钉需 CONFIG_AES_KEY（.env 注入）");
        long pid = createProject();
        long envId = createEnv(pid);
        setEnvConfig(envId, tlsSection(Map.of("mode", "strict",
                "client_cert_pem", "ENC:" + configCipher.encrypt(CLIENT_CERT_PEM))));

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-halfpair");

        assertNull(view.tlsPolicy().clientCertPem(), "私钥缺失=证书一并丢弃（半截凭据不冒充双向认证）");
        assertNull(view.tlsPolicy().clientKeyPem());
        assertFalse(view.tlsPolicy().hasClientCertPair());
    }

    @Test
    void claim_成对mTLS凭据_双双解密下发() {
        Assumptions.assumeTrue(configCipher.isEnabled(), "ENC: 解密钉需 CONFIG_AES_KEY（.env 注入）");
        long pid = createProject();
        long envId = createEnv(pid);
        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("mode", "custom_ca");
        tls.put("ca_pem", "ENC:" + configCipher.encrypt(CA_PEM));
        tls.put("client_cert_pem", "ENC:" + configCipher.encrypt(CLIENT_CERT_PEM));
        tls.put("client_key_pem", "ENC:" + configCipher.encrypt(CLIENT_KEY_PEM));
        setEnvConfig(envId, tlsSection(tls));

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-mtls");

        assertEquals(CLIENT_CERT_PEM, view.tlsPolicy().clientCertPem());
        assertEquals(CLIENT_KEY_PEM, view.tlsPolicy().clientKeyPem());
        assertTrue(view.tlsPolicy().hasClientCertPair());
    }

    @Test
    void claim_脏configJson_不炸领取主链_strict兜底() {
        long pid = createProject();
        long envId = createEnv(pid);
        jdbcTemplate.update("UPDATE tp_project_env SET config_json = '123'::jsonb WHERE id = ?", envId);

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-dirty");

        assertEquals(TlsMode.STRICT, view.tlsPolicy().mode(), "脏数据=失败安全 strict（envVars fail-safe 同构）");
    }

    @Test
    void claim_环境已删_不阻断领取_strict兜底() {
        long pid = createProject();
        long envId = createEnv(pid);
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "TLS删环用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, java.util.List.of()));
        long taskId = execTaskService.create(new ExecTaskCreateInput(pid, info.id(), "v2", ACTION_JSON, envId));
        jdbcTemplate.update("UPDATE tp_exec_task SET env_base_url = 'http://gone.local' WHERE id = ?", taskId);
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-tls-gone", null, null, "web", "chromium", 2, "1.0.0", 0));

        ClaimTaskView view = execTaskService.claim(exId);

        assertNotNull(view, "环境反查失配不阻断领取");
        assertEquals(TlsMode.STRICT, view.tlsPolicy().mode(), "无档案=strict 兜底（默认决议）");
        assertTrue(view.envVars().isEmpty(), "envVars 同边界零回归");
    }

    @Test
    void claim_tls与params正交_envVars零回归() {
        long pid = createProject();
        long envId = createEnv(pid);
        Map<String, Object> config = tlsSection(Map.of("mode", "insecure"));
        config.put("params", Map.of("LEGACY", "plain-value"));
        setEnvConfig(envId, config);

        ClaimTaskView view = claimTask(pid, envId, "ex-tls-ortho");

        assertEquals(Map.of("LEGACY", "plain-value"), view.envVars(), "params 解密链路零回归");
        assertEquals(TlsMode.INSECURE, view.tlsPolicy().mode(), "tls 独立通道");
    }
}
