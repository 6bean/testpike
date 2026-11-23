package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ScriptServiceTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> scriptTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, scriptTrackedProjects);
    }

    private long createScriptServiceProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                scriptTrackedProjects, "脚本项目" + code, code);
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "登录功能测试", "打开系统登录页",
                "登录成功进入首页", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0), new CaseStepInput(2, "页面出现登录成功提示", 1)));
        return caseService.create(1L, input).id();
    }

    private static final String ACTION_JSON = """
            {"version":"v2","caseId":"TC-SPT-001","title":"登录功能测试","endType":"web",
             "setup":[{"action":"goto","target":{"url":"{{ENV:URL}}/login"}}],
             "steps":[{"action":"input","target":{"css":"#username"},"value":"{{DATA:testUser}}"},
                      {"action":"expect","target":{"text":"登录成功"}}],
             "env":{"ENV":"dev"}}
            """;

    @Test
    void save_首次保存_脚本可用() {
        long projectId = createScriptServiceProject("SPT1");
        long caseId = createCase(projectId);

        scriptService.save(new ScriptSaveInput(caseId, "v2", ACTION_JSON, 1));

        ScriptInfo info = scriptService.getByCaseId(caseId);
        assertNotNull(info);
        assertEquals("v2", info.schemaVersion());
        assertEquals(1, info.status());
        assertEquals(1, info.generatedBy());
        assertEquals(1, info.version());
    }

    @Test
    void save_同用例再次保存_upsert覆盖不重复() {
        long projectId = createScriptServiceProject("SPT2");
        long caseId = createCase(projectId);

        scriptService.save(new ScriptSaveInput(caseId, "v2", ACTION_JSON, 1));
        String v2Json = ACTION_JSON.replace("登录成功", "登录成功（v2）");
        scriptService.save(new ScriptSaveInput(caseId, "v2", v2Json, 1));

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script WHERE case_id = ?", Long.class, caseId);
        assertEquals(1L, count);                        // 1:1 UNIQUE(case_id)
        ScriptInfo info = scriptService.getByCaseId(caseId);
        assertEquals(2, info.version());
        assertNotNull(info.actionJson());
    }

    @Test
    void markPending_用例编辑后脚本待更新_不可执行() {
        long projectId = createScriptServiceProject("SPT3");
        long caseId = createCase(projectId);
        scriptService.save(new ScriptSaveInput(caseId, "v2", ACTION_JSON, 1));

        scriptService.markPending(caseId);

        assertEquals(2, scriptService.getByCaseId(caseId).status());
    }

    @Test
    void getByCaseId_无脚本_返回null() {
        assertNull(scriptService.getByCaseId(999999L));
    }


    
    private static String stepsJson(int count) {
        StringBuilder sb = new StringBuilder("{\"version\":\"v2\",\"caseId\":\"TC-LIM\",\"steps\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"action\":\"click\",\"target\":{\"css\":\"#btn").append(i)
                    .append("\",\"chain\":[\"css\"]}}");
        }
        return sb.append("],\"env\":{\"ENV\":\"dev\"}}").toString();
    }

    
    private void setScriptKey(String key, String value) {
        configService.update(1L, configId(key), new ConfigInput(
                key, value, "系统", "int", 0, "测试期临时配置值"));
    }

    @Test
    void save_步骤数超上限_40905人话_不落库() {
        long projectId = createScriptServiceProject("LIM1");
        long caseId = createCase(projectId);
        setScriptKey("ai.script.max-steps", "50");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                scriptService.save(new ScriptSaveInput(caseId, "v2", stepsJson(51), 1)));
        assertEquals(BizErrorCode.SCRIPT_LIMIT_EXCEEDED, ex.getCode());
        assertTrue(ex.getMessage().contains("脚本步骤超限"), "人话: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("51") && ex.getMessage().contains("50"),
                "含实际/上限数值: " + ex.getMessage());
        assertNull(scriptService.getByCaseId(caseId), "违例脚本不得落库");
    }

    @Test
    void save_热更上限后_下次保存即生效() {
        long projectId = createScriptServiceProject("LIM2");
        long caseId = createCase(projectId);
        long stepsConfigId = configId("ai.script.max-steps");
        try {
            configService.update(1L, stepsConfigId, new ConfigInput(
                    "ai.script.max-steps", "15", "系统", "int", 0, "热更探针"));
            assertThrows(ServiceException.class, () ->
                    scriptService.save(new ScriptSaveInput(caseId, "v2", stepsJson(100), 1)));
            configService.update(1L, stepsConfigId, new ConfigInput(
                    "ai.script.max-steps", "200", "系统", "int", 0, "热更探针"));
            scriptService.save(new ScriptSaveInput(caseId, "v2", stepsJson(100), 1));
            assertNotNull(scriptService.getByCaseId(caseId));
        } finally {
            configService.update(1L, stepsConfigId, new ConfigInput(
                    "ai.script.max-steps", "50", "系统", "int", 0, "热更探针"));
        }
    }

    @Test
    void save_loop数超上限_40905人话() {
        long projectId = createScriptServiceProject("LIM3");
        long caseId = createCase(projectId);
        setScriptKey("ai.script.max-loop", "20");
        StringBuilder sb = new StringBuilder(
                "{\"version\":\"v2\",\"caseId\":\"TC-LOOP\",\"steps\":[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"action\":\"loop\",\"count\":2,\"steps\":[]}");
        }
        sb.append("],\"env\":{\"ENV\":\"dev\"}}");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                scriptService.save(new ScriptSaveInput(caseId, "v2", sb.toString(), 1)));
        assertEquals(BizErrorCode.SCRIPT_LIMIT_EXCEEDED, ex.getCode());
        assertTrue(ex.getMessage().contains("循环"), "人话含循环超限: " + ex.getMessage());
    }

    @Test
    void save_嵌套深度超上限_40905人话() {
        long projectId = createScriptServiceProject("LIM4");
        long caseId = createCase(projectId);
        setScriptKey("ai.script.max-nest-depth", "5");
        StringBuilder sb = new StringBuilder(
                "{\"version\":\"v2\",\"caseId\":\"TC-DEPTH\",\"steps\":[");
        for (int i = 0; i < 6; i++) {
            sb.append("{\"action\":\"if\",\"condition\":\"x\",\"steps\":[");
        }
        sb.append("{\"action\":\"click\",\"target\":{\"css\":\"#a\",\"chain\":[\"css\"]}}");
        for (int i = 0; i < 6; i++) {
            sb.append("]}");
        }
        sb.append("],\"env\":{\"ENV\":\"dev\"}}");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                scriptService.save(new ScriptSaveInput(caseId, "v2", sb.toString(), 1)));
        assertEquals(BizErrorCode.SCRIPT_LIMIT_EXCEEDED, ex.getCode());
        assertTrue(ex.getMessage().contains("嵌套"), "人话含嵌套超限: " + ex.getMessage());
    }

    @Test
    void save_动作JSON非法_400人话不落库() {
        long projectId = createScriptServiceProject("LIM5");
        long caseId = createCase(projectId);
        ServiceException ex = assertThrows(ServiceException.class, () ->
                scriptService.save(new ScriptSaveInput(caseId, "v2", "{not-json", 1)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertNull(scriptService.getByCaseId(caseId));
    }

    private long configId(String key) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, key);
        return id;
    }
}
