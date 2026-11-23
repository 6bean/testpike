package com.sixbean.testpike.core.script.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.AiScriptLimitsReader;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.Flowchart;
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
class ScriptPreviewTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AiScriptLimitsReader limitsReader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> previewTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, previewTrackedProjects);
    }

    private long createScriptPreviewProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                previewTrackedProjects, "预览项目" + code, code);
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "预览用例", "打开登录页",
                "登录成功", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号", 0)));
        return caseService.create(1L, input).id();
    }

    
    private static final String VALID_JSON = """
            {"version":"v2","caseId":"TC-PV-001","title":"预览","endType":"web",
             "steps":[{"action":"input","target":{"css":"#userPv01","chain":["css"]},"value":"a"},
                      {"action":"expect","target":{"text":"登录成功"}}],
             "env":{"ENV":"dev"}}
            """;

    
    private static final String BAD_JSON = """
            {"version":"v2","caseId":"TC-PV-002","title":"坏样本","endType":"web",
             "steps":[{"action":"frobnicate","target":{"css":"#x","chain":["css"]}},
                      {"action":"click","target":{"css":"#noChain"}},
                      {"action":"goto","target":{"url":"{{ENV.BASE_URL}}/p"}}],
             "env":{"ENV":"dev"}}
            """;

    
    private String overLimitJson() {
        int n = limitsReader.maxSteps() + 1;
        StringBuilder sb = new StringBuilder(
                "{\"version\":\"v2\",\"caseId\":\"TC-PV-003\",\"title\":\"超限\",\"endType\":\"web\",\"steps\":[");
        for (int i = 0; i < n; i++) {
            sb.append("{\"action\":\"click\",\"target\":{\"css\":\"#s").append(i).append("\",\"chain\":[\"css\"]}}");
            if (i < n - 1) {
                sb.append(',');
            }
        }
        return sb.append("],\"env\":{\"ENV\":\"dev\"}}").toString();
    }

    private long rows(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        assertNotNull(value);
        return value;
    }


    @Test
    void previewFlowchart_合法草稿_出图带步数_零落库零版本行() {
        long caseId = createCase(createScriptPreviewProject("PV1"));
        long scriptRowsBefore = rows("tp_script");
        long versionRowsBefore = rows("tp_script_version");

        Flowchart flowchart = scriptService.previewFlowchart(caseId, VALID_JSON);

        assertTrue(flowchart.mermaid().startsWith("flowchart TD"), "mermaid 头部: " + flowchart.mermaid());
        assertTrue(flowchart.mermaid().contains("userPv01"), "动作应出现在图中: " + flowchart.mermaid());
        assertEquals(2, flowchart.stepCount());
        assertEquals(scriptRowsBefore, rows("tp_script"));
        assertEquals(versionRowsBefore, rows("tp_script_version"));
    }


    @Test
    void previewFlowchart_非法草稿_40001逐项点名_零落库() {
        long caseId = createCase(createScriptPreviewProject("PV2"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptService.previewFlowchart(caseId, BAD_JSON));

        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        String msg = ex.getMessage();
        assertTrue(msg.contains("frobnicate"), "未点名未知动作: " + msg);
        assertTrue(msg.contains("locator.chain"), "未点名缺 chain: " + msg);
        assertTrue(msg.contains("ENV.BASE_URL"), "未点名非法占位: " + msg);
        // fixture discipline : "zero persistence" is judged on this fixture
        // case's own rows; unrelated rows from other fixtures stay out of scope
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script WHERE case_id = ?", Long.class, caseId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script_version WHERE case_id = ?", Long.class, caseId));
    }

    @Test
    void previewFlowchart_三键超限_40905人话() {
        long caseId = createCase(createScriptPreviewProject("PV3"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptService.previewFlowchart(caseId, overLimitJson()));

        assertEquals(BizErrorCode.SCRIPT_LIMIT_EXCEEDED, ex.getCode());
        assertTrue(ex.getMessage().contains("超限"), "人话应含超限字样: " + ex.getMessage());
        assertEquals(0, rows("tp_script"));
    }

    @Test
    void previewFlowchart_草稿空白_400人话() {
        long caseId = createCase(createScriptPreviewProject("PV4"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptService.previewFlowchart(caseId, "  "));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }


    @Test
    void previewFlowchart_用例不存在_404人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptService.previewFlowchart(999_999_999L, VALID_JSON));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }
}
