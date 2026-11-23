package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(
        properties = "testpike.prod-blacklist=black.example.com")
class BatchContextStoreTest {

    @Autowired
    private BatchContextStore batchContextStore;
    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redis;

    // Fixture-scoped teardown (truncate-ratchet discipline, same shape as the
    // appauto dispatch suite): this suite's projects live in the name family
    // below, and the FK-child-first sweep walks everything keyed off them.
    // tp_batch_case and tp_batch_context carry batch ids with NO FK constraint
    // and no project column, so the generic walk cannot see them: purge this
    // family's rows first, joined through the batch rows that still exist at
    // that point. tp_batch_template and tp_resource_lease are project-keyed
    // without an FK into tp_project, so they go through extraProjectTables.
    // The suite's executors (ex-ctx*) purge by name family. tp_user and
    // tp_notification_pref hold shipped rows the old CASCADE wiped — the
    // sweep never touches them.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "项目CTX";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        for (String table : java.util.List.of("tp_batch_case", "tp_batch_context")) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE batch_id IN"
                    + " (SELECT b.id FROM tp_exec_batch b"
                    + "  JOIN tp_project p ON p.id = b.project_id"
                    + "  WHERE p.name LIKE ?)", FIXTURE_PROJECT_NAME_PREFIX + "%");
        }
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of("tp_batch_template", "tp_resource_lease"),
                java.util.List.of(FIXTURE_PROJECT_NAME_PREFIX));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-ctx%'");
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long pid, String baseUrl) {
        return projectEnvService.create(pid, new ProjectEnvInput("测试环境", 2,
                baseUrl, null, "web", "chromium", "v1.0.0"));
    }

    private long createApprovedCaseWithScript(long pid) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "上下文链用例", "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        scriptService.save(new ScriptSaveInput(info.id(), "v2",
                "{\"caseId\":" + info.id() + ",\"steps\":[]}", 2));
        return info.id();
    }

    private BatchInfo startBatch(long pid, long envId, long caseId) {
        BatchInfo batch = batchService.create(1L,
                new BatchCreateInput(pid, "上下文批", envId, List.of(caseId), null));
        batchService.start(1L, batch.id());
        return batchService.get(batch.id());
    }

    private long registerExecutor(String name) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0));
    }

    @Test
    void put_落库行必ENC密文_明文禁落盘() {
        long pid = createProject("CTX1");
        long envId = createEnv(pid, "https://ctx1.example.com");
        BatchInfo batch = startBatch(pid, envId, createApprovedCaseWithScript(pid));

        batchContextStore.put(batch.id(), BatchContextStore.ARTIFACT_PREFIX + "cookie", "SESSION=abc123");

        List<String> stored = jdbcTemplate.queryForList(
                "SELECT value_cipher FROM tp_batch_context WHERE batch_id = ? AND ctx_key = ?",
                String.class, batch.id(), BatchContextStore.ARTIFACT_PREFIX + "cookie");
        assertEquals(1, stored.size(), "工件必须落 tp_batch_context 权威行");
        assertTrue(stored.get(0).startsWith("ENC:"), "落盘值必 ENC 密文（红线4，禁明文入上下文表）");
    }

    @Test
    void redis读穿_miss回源DB再回填_hit直返() {
        long pid = createProject("CTX2");
        long envId = createEnv(pid, "https://ctx2.example.com");
        BatchInfo batch = startBatch(pid, envId, createApprovedCaseWithScript(pid));
        String redisKey = BatchContextStore.REDIS_KEY_PREFIX + batch.id() + ":k1";

        batchContextStore.put(batch.id(), "k1", "v-secret");
        String cached = redis.opsForValue().get(redisKey);
        assertNotNull(cached, "put 提交后 Redis 必回填（afterCommit 语义）");
        assertTrue(cached.startsWith("ENC:"), "缓存值=同一 ENC 信封，禁明文入缓存明文面");

        redis.delete(redisKey);
        String reread = batchContextStore.readEnvelope(batch.id(), "k1");
        assertNotNull(reread, "读穿 miss 必回源 DB");
        assertEquals(cached.length(), reread.length(), "回源信封与原信封同值（解密面一致）");
        assertNotNull(redis.opsForValue().get(redisKey), "回源成功后必再回填缓存");

        assertNull(batchContextStore.readEnvelope(batch.id(), "missing-key"), "无键= null 回落");
    }

    @Test
    void v123表与TTL键seed在位() {
        Integer tableCols = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'tp_batch_context'",
                Integer.class);
        assertNotNull(tableCols);
        assertTrue(tableCols >= 4, "tp_batch_context 表必已落库（V123 手动 psql，漏落=坑三证族）");
        List<String> keys = jdbcTemplate.queryForList(
                "SELECT config_key FROM tp_sys_config WHERE config_key = 'batch.context-ttl-seconds'",
                String.class);
        assertEquals(1, keys.size(), "v2.56 口径 a：上下文 Redis TTL 必 K8 键 seed 在位");
    }

    @Test
    void claim信封携会话工件_解密明文_无工件空map零漂移() {
        long pid = createProject("CTX3");
        long envId = createEnv(pid, "https://ctx3.example.com");
        BatchInfo batch = startBatch(pid, envId, createApprovedCaseWithScript(pid));
        batchContextStore.put(batch.id(), BatchContextStore.ARTIFACT_PREFIX + "cookie",
                "SESSION=cross-machine-1");
        batchContextStore.put(batch.id(), "k-not-artifact", "非工件键不进信封");

        long exId = registerExecutor("ex-ctx3");
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "执行器必须领取到任务");
        assertEquals(Map.of("cookie", "SESSION=cross-machine-1"), view.sessionArtifacts(),
                "claim 信封 sessionArtifacts=该批 artifact:* 键解密明文 map（键去前缀，非工件键不进）");

        long pid2 = createProject("CTX4");
        long envId2 = createEnv(pid2, "https://ctx4.example.com");
        startBatch(pid2, envId2, createApprovedCaseWithScript(pid2));
        long exId2 = registerExecutor("ex-ctx4");
        ClaimTaskView view2 = execTaskService.claim(exId2);
        assertNotNull(view2);
        assertEquals(Map.of(), view2.sessionArtifacts(), "无工件批=空 map（执行器侧零破坏）");
    }
}
