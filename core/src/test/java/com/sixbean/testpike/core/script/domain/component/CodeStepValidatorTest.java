package com.sixbean.testpike.core.script.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CodeStepValidatorTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> codeStepTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, codeStepTrackedProjects);
    }

    private long createCodeStepProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                codeStepTrackedProjects, "B8项目" + code, code);
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "代码步骤用例", "沙箱核对",
                "片段执行正确", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "执行片段", 0)));
        return caseService.create(1L, input).id();
    }

    private static String actionOf(String stepJson) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-B8\",\"title\":\"沙箱片段\",\"endType\":\"web\","
                + "\"steps\":[" + stepJson + "],\"env\":{\"ENV\":\"dev\"}}";
    }

    private ServiceException saveStep(long caseId, String stepJson, Long operatorId) {
        return assertThrows(ServiceException.class, () -> scriptService.save(
                new ScriptSaveInput(caseId, "v2", actionOf(stepJson), 1, operatorId)));
    }

    private static String codeStep(String fields) {
        return "{\"action\":\"code.step\"," + fields + "}";
    }

    @Test
    void save_负责人python干净片段_直通落库() {
        long caseId = createCase(createCodeStepProject("B8OK"));

        scriptService.save(new ScriptSaveInput(caseId, "v2", actionOf(codeStep(
                "\"language\":\"python\",\"code\":\"import json\\nprint(json.dumps({a: 1}))\"")),
                1, 1L));

        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from tp_script where case_id = ?", Integer.class, caseId));
    }

    @Test
    void save_非负责人codeStep_40301人话() {
        long caseId = createCase(createCodeStepProject("B8PERM"));

        ServiceException ex = saveStep(caseId, codeStep(
                "\"language\":\"python\",\"code\":\"print(1)\""), 888L);

        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("项目负责人"), "权限人话缺失：" + ex.getMessage());
    }

    @Test
    void save_缺操作人codeStep_40301() {
        long caseId = createCase(createCodeStepProject("B8NOOP"));

        ServiceException ex = saveStep(caseId, codeStep(
                "\"language\":\"python\",\"code\":\"print(1)\""), null);

        assertEquals(40301, ex.getCode());
    }

    @Test
    void save_js语言_40001人话未开放() {
        long caseId = createCase(createCodeStepProject("B8JS"));

        ServiceException ex = saveStep(caseId, codeStep(
                "\"language\":\"js\",\"code\":\"console.log(1)\""), 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("未开放"), "js 人话缺失：" + ex.getMessage());
    }

    @Test
    void save_禁网片段_40001点名socket() {
        long caseId = createCase(createCodeStepProject("B8NET"));

        ServiceException ex = saveStep(caseId, codeStep(
                "\"language\":\"python\",\"code\":\"import socket\\ns=socket.socket()\""), 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("网络"), "三禁类别点名缺失：" + ex.getMessage());
    }

    @Test
    void save_子进程片段_40001点名subprocess() {
        long caseId = createCase(createCodeStepProject("B8SUB"));

        ServiceException ex = saveStep(caseId, codeStep(
                "\"language\":\"python\",\"code\":\"import subprocess\\nsubprocess.run(['ls'])\""), 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("子进程"), "三禁类别点名缺失：" + ex.getMessage());
    }

    @Test
    void save_timeoutMs超上限_40001点名预算域() {
        long caseId = createCase(createCodeStepProject("B8TO"));

        ServiceException ex = saveStep(caseId, codeStep(
                "\"language\":\"python\",\"code\":\"print(1)\",\"timeoutMs\":60000"), 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("timeoutMs"), "字段点名缺失：" + ex.getMessage());
    }

    @Test
    void save_if嵌套内codeStep违例_同拦不逃逸() {
        long caseId = createCase(createCodeStepProject("B8NEST"));

        ServiceException ex = saveStep(caseId,
                "{\"action\":\"if\",\"condition\":\"1==1\",\"steps\":["
                        + codeStep("\"language\":\"python\",\"code\":\"open('a')\"") + "]}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("文件"), "嵌套链路三禁点名缺失：" + ex.getMessage());
    }
}
