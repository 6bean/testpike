package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.NonUiActionContract;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.prod-blacklist=*.prod-demo.com")
class NonUiStepValidatorTest {

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> nonUiTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, nonUiTrackedProjects);
    }

    
    private long createNonUiProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                nonUiTrackedProjects, "B11项目" + code, code);
    }

    private long createCase(long projectId) {
        CaseCreateInput input = new CaseCreateInput(projectId, "落库校验用例", "下单后核对",
                "状态落库正确", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "查看订单", 0)));
        return caseService.create(1L, input).id();
    }

    
    private static String actionOf(String stepJson) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-B11\",\"title\":\"落库校验\",\"endType\":\"web\","
                + "\"steps\":[" + stepJson + "],\"env\":{\"ENV\":\"dev\"}}";
    }

    private ServiceException saveStep(long caseId, String stepJson, Long operatorId) {
        return assertThrows(ServiceException.class, () -> scriptService.save(
                new ScriptSaveInput(caseId, "v2", actionOf(stepJson), 1, operatorId)));
    }

    private void saveOk(long caseId, String stepJson, Long operatorId) {
        scriptService.save(new ScriptSaveInput(caseId, "v2", actionOf(stepJson), 1, operatorId));
    }


    @Test
    void save_sqlAssert首词非SELECT_400点名() {
        long caseId = createCase(createNonUiProject("B11A"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.assert\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"UPDATE t_order SET status='X' WHERE id=1\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("SELECT"), "首词白名单文案缺失：" + ex.getMessage());
    }

    @Test
    void save_sql多语句分号_400拒() {
        long caseId = createCase(createNonUiProject("B11B"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.assert\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"SELECT 1; SELECT 2\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("一条语句"), "多语句点名缺失：" + ex.getMessage());
    }

    @Test
    void save_sql具名参数未绑定_400点名参数名() {
        long caseId = createCase(createNonUiProject("B11C"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.assert\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"SELECT status FROM t_order WHERE order_no=:orderNo\","
                + "\"params\":{\"other\":1}}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("orderNo"), "参数点名缺失：" + ex.getMessage());
    }

    @Test
    void save_sql文本内插占位符_400拒拼接面() {
        long caseId = createCase(createNonUiProject("B11D"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.assert\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"SELECT status FROM t_order WHERE order_no='{{DATA:no}}'\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("内插"), "禁拼接文案缺失：" + ex.getMessage());
    }

    @Test
    void save_sqlExec黑名单词TRUNCATE_400拒() {
        long caseId = createCase(createNonUiProject("B11E"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.exec\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"TRUNCATE TABLE t_order\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("TRUNCATE"), "黑名单词点名缺失：" + ex.getMessage());
    }

    @Test
    void save_sqlExec的UPDATE无WHERE_400拒() {
        long caseId = createCase(createNonUiProject("B11F"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.exec\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"UPDATE t_order SET status='DONE'\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("WHERE"), "WHERE 点名缺失：" + ex.getMessage());
    }

    @Test
    void save_sqlExec缺conn_400点名() {
        long caseId = createCase(createNonUiProject("B11G"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.assert\","
                + "\"sql\":\"SELECT 1\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("conn"), "conn 点名缺失：" + ex.getMessage());
    }


    @Test
    void save_sqlExec非负责人_40301人话() {
        long caseId = createCase(createNonUiProject("B11H"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"sql.exec\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"INSERT INTO t_order (no) VALUES (:no)\",\"params\":{\"no\":\"A1\"}}", 888L);

        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("负责人"), "人话文案缺失：" + ex.getMessage());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_script WHERE case_id = ?", Integer.class, caseId));
    }

    @Test
    void save_sqlExec负责人合法DML_通过落库() {
        long caseId = createCase(createNonUiProject("B11I"));

        saveOk(caseId, "{\"action\":\"sql.exec\",\"conn\":\"DB_SIT\","
                + "\"sql\":\"INSERT INTO t_order (no) VALUES (:no)\",\"params\":{\"no\":\"A1\"}}", 1L);

        assertEquals(1, scriptService.getByCaseId(caseId).version());
    }


    @Test
    void save_JsonPath的filter表达式_400点名受限子集() {
        long caseId = createCase(createNonUiProject("B11J"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"net.assert\","
                + "\"match\":{\"urlPattern\":\"*/api/v1/orders*\"},"
                + "\"expect\":{\"status\":200,\"body\":[{\"path\":\"$.data[?(@.id>1)]\","
                + "\"matchType\":\"contains\",\"value\":\"x\"}]}}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("受限子集") && ex.getMessage().contains("$.a.b[0].c")
                        || ex.getMessage().contains("$.data.items[0]"),
                "子集示例点名缺失：" + ex.getMessage());
    }

    @Test
    void save_netAssert的urlPattern正则元字符_400拒() {
        long caseId = createCase(createNonUiProject("B11K"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"net.assert\","
                + "\"match\":{\"urlPattern\":\"api/(v1|v2)/orders\"}}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("glob"), "glob 文案缺失：" + ex.getMessage());
    }


    @Test
    void save_httpRequest生产URL_40301人话() {
        long caseId = createCase(createNonUiProject("B11L"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"http.request\",\"method\":\"GET\","
                + "\"url\":\"https://shop.prod-demo.com/api/jobs/1\"}", 1L);

        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("prod-demo.com"), "域名点名缺失：" + ex.getMessage());
    }

    @Test
    void save_httpRequest白名单外_400点名配置键() {
        long caseId = createCase(createNonUiProject("B11M"));
        configCacheHolder.put("exec.http.allow-hosts", "api.internal,*.allowed.com");

        ServiceException ex = saveStep(caseId, "{\"action\":\"http.request\",\"method\":\"GET\","
                + "\"url\":\"http://other.example.com/api/x\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("exec.http.allow-hosts"),
                "配置键点名缺失：" + ex.getMessage());
    }

    @Test
    void save_httpRequest白名单内与poll合法_通过落库() {
        long caseId = createCase(createNonUiProject("B11N"));
        configCacheHolder.put("exec.http.allow-hosts", "api.internal");

        saveOk(caseId, "{\"action\":\"http.request\",\"method\":\"GET\","
                + "\"url\":\"http://api.internal/jobs/1\",\"poll\":{\"intervalMs\":2000,"
                + "\"timeoutMs\":60000,\"until\":\"match\"},\"expect\":{\"status\":200,"
                + "\"body\":[{\"path\":\"$.state\",\"matchType\":\"regex\","
                + "\"pattern\":\"^(DONE|SUCCESS)$\"}]},\"saveAs\":\"resp1\"}", 1L);

        assertEquals(1, scriptService.getByCaseId(caseId).version());
    }

    @Test
    void save_consoleAssert的mode枚举外_400人话() {
        long caseId = createCase(createNonUiProject("B11O"));

        ServiceException ex = saveStep(caseId, "{\"action\":\"console.assert\",\"mode\":\"strict\"}", 1L);

        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("fail") && ex.getMessage().contains("collect"),
                "可选值清单缺失：" + ex.getMessage());
    }


    @Test
    void isAssert映射_六动作四则零歧义() throws Exception {
        JsonNode withExpect = new ObjectMapper().readTree(
                "{\"action\":\"http.request\",\"expect\":{\"status\":200}}");
        JsonNode withoutExpect = new ObjectMapper().readTree(
                "{\"action\":\"http.request\",\"method\":\"GET\"}");

        assertEquals(Boolean.TRUE, NonUiActionContract.isAssert("console.assert", null));
        assertEquals(Boolean.TRUE, NonUiActionContract.isAssert("net.assert", null));
        assertEquals(Boolean.TRUE, NonUiActionContract.isAssert("sql.assert", null));
        assertEquals(Boolean.TRUE, NonUiActionContract.isAssert("http.request", withExpect));
        assertEquals(Boolean.FALSE, NonUiActionContract.isAssert("http.request", withoutExpect));
        assertEquals(Boolean.FALSE, NonUiActionContract.isAssert("sql.exec", null));
        assertEquals(Boolean.FALSE, NonUiActionContract.isAssert("sql.query", null));
        assertEquals(null, NonUiActionContract.isAssert("click", null));
    }


    @Test
    void save_存量v2纯UI脚本_零漂移() {
        long caseId = createCase(createNonUiProject("B11P"));

        saveOk(caseId, "{\"action\":\"click\",\"target\":{\"css\":\"#login\"}}", 888L);
        saveOk(caseId, "{\"action\":\"expect\",\"target\":{\"text\":\"登录成功\"}}", null);
        saveOk(caseId, "{\"action\":\"loop\",\"items\":[\"a\"],\"steps\":[{\"action\":\"click\","
                + "\"target\":{\"css\":\".row\"}}]}", 888L);

        assertEquals(3, scriptService.getByCaseId(caseId).version());
    }
}
