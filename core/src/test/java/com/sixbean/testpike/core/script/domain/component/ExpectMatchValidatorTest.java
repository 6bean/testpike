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
class ExpectMatchValidatorTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> expectMatchTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, expectMatchTrackedProjects);
    }

    
    private long createExpectMatchProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                expectMatchTrackedProjects, "B10项目" + code, code);
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "金额断言用例", "打开订单页",
                "金额展示正确", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "查看金额", 0)));
        return caseService.create(1L, input).id();
    }

    
    private static String actionOf(String stepJson) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-B10\",\"title\":\"金额断言\",\"endType\":\"web\","
                + "\"steps\":[" + stepJson + "],\"env\":{\"ENV\":\"dev\"}}";
    }

    private ServiceException saveExpect(long caseId, String stepJson, Long operatorId) {
        return assertThrows(ServiceException.class, () -> scriptService.save(
                new ScriptSaveInput(caseId, "v2", actionOf(stepJson), 1, operatorId)));
    }


    @Test
    void save_range缺min和max_400人话至少给定一端() {
        long projectId = createExpectMatchProject("B10A");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"css\":\"td.amount\"},\"matchType\":\"range\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("区间") && ex.getMessage().contains("至少"),
                "人话文案缺失：" + ex.getMessage());
    }

    @Test
    void save_range的min非数值_400点名数值() {
        long projectId = createExpectMatchProject("B10B");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"css\":\"td.amount\"},"
                        + "\"matchType\":\"range\",\"min\":\"abc\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("数值"), "人话文案缺失：" + ex.getMessage());
    }


    @Test
    void save_matchType枚举外_400人话含可选值() {
        long projectId = createExpectMatchProject("B10C");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"text\":\"x\"},\"matchType\":\"fuzzy\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("contains") && ex.getMessage().contains("regex")
                        && ex.getMessage().contains("range"),
                "可选值清单缺失：" + ex.getMessage());
    }

    @Test
    void save_bounds枚举外_400人话含可选值() {
        long projectId = createExpectMatchProject("B10D");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"css\":\"td.amount\"},"
                        + "\"matchType\":\"range\",\"min\":100,\"bounds\":\"half-open\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("closed") && ex.getMessage().contains("left-open"),
                "可选值清单缺失：" + ex.getMessage());
    }


    @Test
    void save_pattern超200字符_400点名长度() {
        long projectId = createExpectMatchProject("B10E");
        long caseId = createCase(projectId);
        String longPattern = "a{2}".repeat(60);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"text\":\"x\"},\"matchType\":\"regex\","
                        + "\"pattern\":\"" + longPattern + "\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("200"), "长度点名缺失：" + ex.getMessage());
    }

    @Test
    void save_嵌套量词正则_400人话拒() {
        long projectId = createExpectMatchProject("B10F");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"text\":\"x\"},\"matchType\":\"regex\","
                        + "\"pattern\":\"(a+)+$\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("嵌套量词"), "嵌套量词点名缺失：" + ex.getMessage());
    }

    @Test
    void save_非法正则本体_400人话拒() {
        long projectId = createExpectMatchProject("B10G");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"text\":\"x\"},\"matchType\":\"regex\","
                        + "\"pattern\":\"([unclosed\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("正则"), "人话文案缺失：" + ex.getMessage());
    }

    @Test
    void save_非项目负责人保存regex_40301人话() {
        long projectId = createExpectMatchProject("B10H");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"text\":\"x\"},\"matchType\":\"regex\","
                        + "\"pattern\":\"^ORD-\\\\d{6}$\"}", 888L);

        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("项目负责人"), "人话文案缺失：" + ex.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script WHERE case_id = ?", Integer.class, caseId));
    }

    @Test
    void save_operatorId缺失保存regex_40301人话() {
        long projectId = createExpectMatchProject("B10I");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"expect\",\"target\":{\"text\":\"x\"},\"matchType\":\"regex\","
                        + "\"pattern\":\"^ORD-\\\\d{6}$\"}", null);

        assertEquals(40301, ex.getCode());
    }


    @Test
    void save_loop嵌套子步的非法range同样400() {
        long projectId = createExpectMatchProject("B10J");
        long caseId = createCase(projectId);

        ServiceException ex = saveExpect(caseId,
                "{\"action\":\"loop\",\"items\":[\"a\"],\"steps\":[{\"action\":\"expect\","
                        + "\"target\":{\"css\":\"td.amount\"},\"matchType\":\"range\"}]}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("至少"), "人话文案缺失：" + ex.getMessage());
    }


    @Test
    void save_缺省无matchType字段_老语义零漂移() {
        long projectId = createExpectMatchProject("B10K");
        long caseId = createCase(projectId);

        scriptService.save(new ScriptSaveInput(caseId, "v2", actionOf(
                "{\"action\":\"expect\",\"target\":{\"text\":\"登录成功\"}}"), 1, 888L));
        scriptService.save(new ScriptSaveInput(caseId, "v2", actionOf(
                "{\"action\":\"expect\",\"target\":{\"text\":\"登录成功\",\"exact\":true}}"), 1, null));

        assertEquals(2, scriptService.getByCaseId(caseId).version());
    }

    @Test
    void save_负责人合法range与regex_通过落库() {
        long projectId = createExpectMatchProject("B10L");
        long caseId = createCase(projectId);

        scriptService.save(new ScriptSaveInput(caseId, "v2", actionOf(
                "{\"action\":\"expect\",\"target\":{\"css\":\"td.amount\"},"
                        + "\"matchType\":\"range\",\"min\":100,\"max\":200,\"bounds\":\"open\"}"), 1, 1L));
        scriptService.save(new ScriptSaveInput(caseId, "v2", actionOf(
                "{\"action\":\"expect\",\"target\":{\"text\":\"x\"},\"matchType\":\"regex\","
                        + "\"pattern\":\"^ORD-\\\\d{6}$\"}"), 1, 1L));

        assertEquals(2, scriptService.getByCaseId(caseId).version());
    }
}
