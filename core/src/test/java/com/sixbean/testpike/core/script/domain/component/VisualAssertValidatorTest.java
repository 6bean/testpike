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
class VisualAssertValidatorTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> visualAssertTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, visualAssertTrackedProjects);
    }

    private long createCase(String code) {
        long projectId = ProjectFixtureRig.createSelfHeal(jdbcTemplate,
                projectService, 1L, visualAssertTrackedProjects, "B7项目" + code, code);
        CaseCreateInput input = new CaseCreateInput(projectId, "视觉断言用例", "基线核对",
                "区域容差正确", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "视觉比对", 0)));
        return caseService.create(1L, input).id();
    }

    private static String actionOf(String stepJson) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-B7\",\"title\":\"视觉回归\",\"endType\":\"web\","
                + "\"steps\":[" + stepJson + "],\"env\":{\"ENV\":\"dev\"}}";
    }

    private static String visualStep(String fields) {
        return "{\"action\":\"visual.assert\"," + fields + "}";
    }

    @Test
    void save_干净visual步_直通落库() {
        long caseId = createCase("B7OK");

        scriptService.save(new ScriptSaveInput(caseId, "v2", actionOf(
                visualStep("\"tolerance\":0.02,\"region\":{\"x\":0,\"y\":0,\"w\":800,\"h\":600}")),
                1, 1L));

        assertEquals(1, jdbcTemplate.queryForObject(
                "select count(*) from tp_script where case_id = ?", Integer.class, caseId));
    }

    @Test
    void save_容差越界_40001人话点名() {
        long caseId = createCase("B7TOL");

        ServiceException ex = assertThrows(ServiceException.class, () -> scriptService.save(
                new ScriptSaveInput(caseId, "v2", actionOf(visualStep("\"tolerance\":2")), 1, 1L)));

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("tolerance"), "字段点名缺失：" + ex.getMessage());
    }

    @Test
    void save_区域形状残缺_40001点名region() {
        long caseId = createCase("B7REG");

        ServiceException ex = assertThrows(ServiceException.class, () -> scriptService.save(
                new ScriptSaveInput(caseId, "v2",
                        actionOf(visualStep("\"region\":{\"x\":0,\"y\":5}")), 1, 1L)));

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("region"), "字段点名缺失：" + ex.getMessage());
    }

    @Test
    void save_if嵌套内visual违规_不逃逸() {
        long caseId = createCase("B7NEST");
        String nested = "{\"action\":\"if\",\"condition\":\"a == 1\",\"steps\":["
                + visualStep("\"baselineKey\":\"   \"") + "]}";

        ServiceException ex = assertThrows(ServiceException.class, () -> scriptService.save(
                new ScriptSaveInput(caseId, "v2", actionOf(nested), 1, 1L)));

        assertEquals(40001, ex.getCode());
    }
}
