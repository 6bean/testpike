package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class T235bDataVarsClaimTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

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

    // Fixture-scoped teardown : projects carry the fixed name
    // FK-tree sweep, and tp_data_pool_item (project-keyed, no FK into tp_project)
    // is passed as an extra project table. Executors drop by 'ex-v7b-%'.
    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                java.util.List.of("tp_data_pool_item"),
                jdbcTemplate.queryForList(
                        "SELECT id FROM tp_project WHERE name LIKE '项目V7b%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-v7b-%'");
    }

    private long createProject() {
        return projectService.create(1L, new ProjectCreateInput("项目V7b", "V7b" + System.nanoTime() % 100000,
                "zh-CN", "P1", null));
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
    }

    private long createApprovedCase(long projectId) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, "造数引用用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, java.util.List.of()));
        return info.id();
    }

    
    private void insertPoolItem(long projectId, long envId, String name, String plain,
                                String type, int status) {
        String encrypted = configCipher.isEnabled() ? configCipher.encrypt(plain) : plain;
        jdbcTemplate.update("""
                INSERT INTO tp_data_pool_item (project_id, env_id, type, value, value_hash, name, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """, projectId, envId, type, encrypted, sha256(plain), name, status);
    }

    private static String sha256(String v) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ClaimTaskView claimTask(long projectId, long envId, String exName) {
        long caseId = createApprovedCase(projectId);
        execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2", ACTION_JSON, envId));
        long exId = executorService.register(new ExecutorRegisterInput(
                exName, null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "执行器必须领取到任务");
        return view;
    }

    @Test
    void claim_dataVars键在位_无池项时空map非null() {
        long pid = createProject();
        long envId = createEnv(pid);

        ClaimTaskView view = claimTask(pid, envId, "ex-v7b-empty");

        assertNotNull(view.dataVars(), "dataVars 键必须在位（空=空 map 非 null，同 envVars 契约稳定）");
        assertTrue(view.dataVars().isEmpty(), "无池项=空 map");
    }

    @Test
    void claim_可用池项_按name解密明文下发() {
        Assumptions.assumeTrue(configCipher.isEnabled(), "密文解密钉需 CONFIG_AES_KEY（.env 注入）");
        long pid = createProject();
        long envId = createEnv(pid);
        insertPoolItem(pid, envId, "ACCOUNT", "qa_pool_42", "账号", 1);
        insertPoolItem(pid, envId, "ORDER_NO", "SO-2026-9", "订单", 1);

        ClaimTaskView view = claimTask(pid, envId, "ex-v7b-hit");

        assertEquals(Map.of("ACCOUNT", "qa_pool_42", "ORDER_NO", "SO-2026-9"),
                view.dataVars(), "dataVars=池键名→解密明文 map");
        String stored = jdbcTemplate.queryForObject(
                "SELECT value FROM tp_data_pool_item WHERE project_id = ? AND name = 'ACCOUNT'",
                String.class, pid);
        assertFalse(stored.contains("qa_pool_42"), "DB 侧保持密文零明文（下发时解密不落库，红线4）");
    }

    @Test
    void claim_占用中与失效项_不下发() {
        long pid = createProject();
        long envId = createEnv(pid);
        insertPoolItem(pid, envId, "FREE_K", "v-free", "账号", 1);
        insertPoolItem(pid, envId, "LOCKED_K", "v-locked", "账号", 2);
        insertPoolItem(pid, envId, "DEAD_K", "v-dead", "账号", 3);

        ClaimTaskView view = claimTask(pid, envId, "ex-v7b-status");

        assertEquals(Map.of("FREE_K", "v-free"), view.dataVars(),
                "占用中/失效项禁下发（防双引用脏数据）");
    }

    @Test
    void claim_存量无键名项_不进dataVars() {
        long pid = createProject();
        long envId = createEnv(pid);
        insertPoolItem(pid, envId, null, "legacy-no-key", "账号", 1);
        insertPoolItem(pid, envId, "KEYED", "has-key", "账号", 1);

        ClaimTaskView view = claimTask(pid, envId, "ex-v7b-legacy");

        assertEquals(Map.of("KEYED", "has-key"), view.dataVars(), "NULL 键名存量项排除，可引用项照常");
    }

    @Test
    void claim_他项目他环境池项_不串发() {
        long pidA = createProject();
        long envA = createEnv(pidA);
        insertPoolItem(pidA, envA, "K_A", "val-a", "账号", 1);
        long pidB = createProject();
        long envB = createEnv(pidB);
        insertPoolItem(pidB, envB, "K_B", "val-b", "账号", 1);

        ClaimTaskView view = claimTask(pidA, envA, "ex-v7b-iso");

        assertEquals(Map.of("K_A", "val-a"), view.dataVars(), "只下发本任务项目+环境池项（H1 隔离）");
    }

    @Test
    void claim_envVars回归_dataVars正交零污染() {
        long pid = createProject();
        long envId = createEnv(pid);
        jdbcTemplate.update("UPDATE tp_project_env SET config_json = '{\"params\":{\"LEGACY\":\"plain-value\"}}'::jsonb WHERE id = ?", envId);
        insertPoolItem(pid, envId, "DATA_K", "dval", "账号", 1);

        ClaimTaskView view = claimTask(pid, envId, "ex-v7b-ortho");

        assertEquals(Map.of("LEGACY", "plain-value"), view.envVars(), "envVars 既有语义零回归");
        assertEquals(Map.of("DATA_K", "dval"), view.dataVars(), "dataVars 独立通道");
    }
}
