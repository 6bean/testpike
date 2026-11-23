package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.component.ScriptVersionSupport;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ScriptManualEditTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> manualEditTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, manualEditTrackedProjects);
    }

    private long createManualEditProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                manualEditTrackedProjects, "手编项目" + code, code);
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "手编用例", "打开登录页",
                "登录成功", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号", 0)));
        return caseService.create(1L, input).id();
    }

    
    private static final String VALID_JSON = """
            {"version":"v2","caseId":"TC-ME-001","title":"手编","endType":"web",
             "steps":[{"action":"input","target":{"css":"#userMe01","chain":["css"]},"value":"a"},
                      {"action":"expect","target":{"text":"登录成功"}}],
             "env":{"ENV":"dev"}}
            """;

 
    private static final String BAD_JSON = """
            {"version":"v2","caseId":"TC-ME-002","title":"坏样本","endType":"web",
             "steps":[{"action":"frobnicate","target":{"css":"#x","chain":["css"]}},
                      {"action":"click","target":{"css":"#noChain"}},
                      {"action":"goto","target":{"url":"{{ENV.BASE_URL}}/p"}}],
             "env":{"ENV":"dev"}}
            """;

    private ScriptSaveInput manual(long caseId, String json) {
        return new ScriptSaveInput(caseId, "v2", json, 4, 1L, null);
    }


    @Test
    void save_手工编辑来源_落库generatedBy4() {
        long caseId = createCase(createManualEditProject("ME1"));

        ScriptInfo info = scriptService.save(manual(caseId, VALID_JSON));

        assertEquals(4, info.generatedBy());
        assertEquals(1, info.version());
        Integer dbValue = jdbcTemplate.queryForObject(
                "SELECT generated_by FROM tp_script WHERE case_id = ?", Integer.class, caseId);
        assertEquals(4, dbValue);
    }


    @Test
    void save_手编非法动作_40001逐项点名_零落库零版本行() {
        long caseId = createCase(createManualEditProject("ME2"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptService.save(manual(caseId, BAD_JSON)));

        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        String msg = ex.getMessage();
        assertTrue(msg.contains("frobnicate"), "未点名未知动作: " + msg);
        assertTrue(msg.contains("locator.chain"), "未点名缺 chain: " + msg);
        assertTrue(msg.contains("ENV.BASE_URL"), "未点名非法占位: " + msg);
        assertEquals(0, count("SELECT count(*) FROM tp_script WHERE case_id = ?", caseId));
        assertEquals(0, count("SELECT count(*) FROM tp_script_version WHERE case_id = ?", caseId));
    }

    @Test
    void save_生成链来源_第四条链路不介入零漂移() {
        long caseId = createCase(createManualEditProject("ME3"));
        String legacyPayload = """
                {"version":"v2","caseId":"TC-LEG","steps":[{"action":"input","target":{"css":"#u"},"value":"x"}],"env":{"ENV":"dev"}}
                """;

        scriptService.save(new ScriptSaveInput(caseId, "v2", legacyPayload, 1));

        assertEquals(1, scriptService.getByCaseId(caseId).generatedBy());
    }


    @Test
    void save_乐观锁陈旧_40901人话含当前版本() {
        long caseId = createCase(createManualEditProject("ME4"));
        scriptService.save(manual(caseId, VALID_JSON)); // version=1

        ServiceException ex = assertThrows(ServiceException.class, () -> scriptService.save(
                new ScriptSaveInput(caseId, "v2", VALID_JSON.replace("手编", "手编改"), 4, 1L, 99)));

        assertEquals(BizErrorCode.VERSION_CONFLICT, ex.getCode());
        assertTrue(ex.getMessage().contains("1"), "人话应含当前版本: " + ex.getMessage());
        assertEquals(1, scriptService.getByCaseId(caseId).version());
    }

    @Test
    void save_乐观锁命中_version加1() {
        long caseId = createCase(createManualEditProject("ME5"));
        scriptService.save(manual(caseId, VALID_JSON)); // version=1

        ScriptInfo info = scriptService.save(
                new ScriptSaveInput(caseId, "v2", VALID_JSON.replace("手编", "手编改"), 4, 1L, 1));

        assertEquals(2, info.version());
    }


    @Test
    void save_手编快照_reason为manual_edit() {
        long caseId = createCase(createManualEditProject("ME6"));

        scriptService.save(manual(caseId, VALID_JSON));

        String reason = jdbcTemplate.queryForObject(
                "SELECT reason FROM tp_script_version WHERE case_id = ? ORDER BY id DESC LIMIT 1",
                String.class, caseId);
        assertEquals(ScriptVersionSupport.REASON_MANUAL_EDIT, reason);
    }


    @Test
    void save_手编审计_scriptManualEdit_仅digest零全文() {
        long caseId = createCase(createManualEditProject("ME7"));

        scriptService.save(manual(caseId, VALID_JSON));
        String revised = VALID_JSON.replace("手编", "手编改").replace("登录成功", "改后断言");
        scriptService.save(new ScriptSaveInput(caseId, "v2", revised, 4, 1L, null));

        List<String> rows = jdbcTemplate.query(
                """
                SELECT action, before_json::text, after_json::text FROM tp_audit_log
                WHERE object_type = 'script' AND object_id = ? ORDER BY id
                """,
                (rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3),
                String.valueOf(caseId));
        assertEquals(2, rows.size(), "两次手编保存各写一条审计");
        for (String row : rows) {
            assertTrue(row.startsWith("script_manual_edit|"), "动作名应为 script_manual_edit: " + row);
            String snapshot = row.substring(row.indexOf('|') + 1);
            assertTrue(snapshot.contains("digest"), "快照应含 digest 字段: " + snapshot);
            assertTrue(!snapshot.contains("userMe01") && !snapshot.contains("#u")
                    && !snapshot.contains("登录成功") && !snapshot.contains("改后断言"),
                    "审计快照泄露 action_json 全文: " + snapshot);
        }
    }

    @Test
    void save_非手编来源_不写手编审计() {
        long caseId = createCase(createManualEditProject("ME8"));

        scriptService.save(new ScriptSaveInput(caseId, "v2", VALID_JSON, 1));

        // fixture discipline : scope the no-audit assertion to this fixture case
        // instead of a global table count that a TRUNCATE used to guarantee
        assertEquals(0, count("SELECT count(*) FROM tp_audit_log WHERE action = 'script_manual_edit' "
                + "AND object_type = 'script' AND object_id = ?", String.valueOf(caseId)));
    }


    
    private static final String STEPS_ONLY_JSON = """
            {"steps":[{"action":"click","target":{"css":"#btnT341","chain":["css"]}}]}
            """;

    private long insertEnv(long projectId, String name, int envType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type) VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, name, envType);
    }

    
    private void insertRunHistory(long projectId, long caseId, long envId) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, env_id, name) VALUES (?, ?, 'T341批次') RETURNING id",
                Long.class, projectId, envId);
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, batch_id) VALUES (?, ?, ?)",
                projectId, caseId, batchId);
    }

    private com.fasterxml.jackson.databind.JsonNode parseStored(String actionJson) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(actionJson);
    }

    @Test
    void save_手编缺信封三键_服务端补信封保存成功() throws Exception {
        long projectId = createManualEditProject("T1");
        long caseId = createCase(projectId);
        insertEnv(projectId, "sit", 2);
        String caseNo = jdbcTemplate.queryForObject(
                "SELECT case_no FROM tp_test_case WHERE id = ?", String.class, caseId);

        ScriptInfo info = scriptService.save(manual(caseId, STEPS_ONLY_JSON));

        assertEquals(1, info.version(), "缺信封不得再被 400 拒（用户报障面）");
        com.fasterxml.jackson.databind.JsonNode root = parseStored(info.actionJson());
        assertEquals("v2", root.path("version").asText(), "version=单源常量回填");
        assertEquals(caseNo, root.path("caseId").asText(), "caseId=被存用例编号回填");
        assertEquals("sit", root.path("env").path("ENV").asText(), "env=项目环境带入");
    }

    @Test
    void save_手编缺env_优先带入最近使用批次环境() throws Exception {
        long projectId = createManualEditProject("T2");
        long caseId = createCase(projectId);
        insertEnv(projectId, "dev-first", 1);
        long sitId = insertEnv(projectId, "sit-recent", 2);
        insertRunHistory(projectId, caseId, sitId);

        ScriptInfo info = scriptService.save(manual(caseId, STEPS_ONLY_JSON));

        com.fasterxml.jackson.databind.JsonNode root = parseStored(info.actionJson());
        assertEquals("sit-recent", root.path("env").path("ENV").asText(),
                "最近使用环境优先于项目首个环境");
    }

    @Test
    void save_手编项目无环境_env补空对象不阻断保存() throws Exception {
        long projectId = createManualEditProject("T3");
        long caseId = createCase(projectId);

        ScriptInfo info = scriptService.save(manual(caseId, STEPS_ONLY_JSON));

        com.fasterxml.jackson.databind.JsonNode root = parseStored(info.actionJson());
        assertTrue(root.path("env").isObject(), "无环境可带时补空 env 对象（存在即过契约）");
    }

    @Test
    void save_手编已有信封键_零覆写保持用户内容() throws Exception {
        long projectId = createManualEditProject("T4");
        long caseId = createCase(projectId);
        insertEnv(projectId, "sit", 2);
        String payload = """
                {"version":"v2","caseId":"TC-KEEP-T4","steps":[{"action":"click","target":{"css":"#k","chain":["css"]}}],"env":{"ENV":"dev"}}
                """;

        ScriptInfo info = scriptService.save(manual(caseId, payload));

        com.fasterxml.jackson.databind.JsonNode root = parseStored(info.actionJson());
        assertEquals("TC-KEEP-T4", root.path("caseId").asText(), "已有信封值不覆写");
        assertEquals("dev", root.path("env").path("ENV").asText(), "已有 env 不覆写");
    }

    @Test
    void save_手编步骤违例_点名可见步骤禁报隐形字段() {
        long projectId = createManualEditProject("T5");
        long caseId = createCase(projectId);
        insertEnv(projectId, "sit", 2);
        String badSteps = """
                {"steps":[{"action":"frobnicate","target":{"css":"#x","chain":["css"]}},
                          {"action":"click","target":{"css":"#noChain"}}]}
                """;

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptService.save(manual(caseId, badSteps)));

        String msg = ex.getMessage();
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(msg.contains("第 1 步") && msg.contains("frobnicate"), "步骤错误须点名可见步号: " + msg);
        assertTrue(msg.contains("第 2 步") && msg.contains("locator.chain"), "缺 chain 须点名步号: " + msg);
        assertTrue(!msg.contains("version 必须") && !msg.contains("caseId 必填") && !msg.contains("env 必填"),
                "信封已自动补齐，禁再报界面无此字段的隐形键错误: " + msg);
    }

    @Test
    void preview_草稿缺信封_与手编保存同套补齐不拒隐形字段() {
        long projectId = createManualEditProject("T6");
        long caseId = createCase(projectId);
        insertEnv(projectId, "sit", 2);

        var flowchart = scriptService.previewFlowchart(caseId, STEPS_ONLY_JSON);

        assertNotNull(flowchart, "预览链必经同套信封补齐（预览非旁路=校验同口径，隐形键不得拒）");
    }

    private int count(String sql, Object... args) {
        Long value = args.length == 0 ? jdbcTemplate.queryForObject(sql, Long.class)
                : jdbcTemplate.queryForObject(sql, Long.class, args);
        assertNotNull(value);
        return value.intValue();
    }
}
