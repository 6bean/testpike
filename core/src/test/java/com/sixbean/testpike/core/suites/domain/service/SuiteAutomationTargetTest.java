package com.sixbean.testpike.core.suites.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.suites.api.dto.SuiteCreateInput;
import com.sixbean.testpike.core.suites.api.dto.SuiteInfo;
import com.sixbean.testpike.core.suites.api.dto.SuiteUpdateInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class SuiteAutomationTargetTest {

    @Autowired
    private RegressionSuiteService suiteService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AppautoEnvProfileService envProfileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects created by this class, swept by id tree at each hook (R37②). */
    private final List<Long> fixtureProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37②) through the suites-family single
        // source; tp_appauto_env_profile/tp_project_env carry real FKs so the
        // generic walk reaches them, tp_regression_suite rides the extra set.
        // This class creates no testers, so the user channel stays empty.
        SharedDbFixtureSweep.suitesTreeDown(jdbcTemplate, List.of(), fixtureProjects);
    }

    private long createProject(String code) {
        return SharedDbFixtureSweep.createSuiteFixtureProject(projectService, jdbcTemplate,
                code, fixtureProjects);
    }

    private long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    private long createEnv(long projectId, String name) {
        Long eid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot)
                VALUES (?, ?, ?, ?, 'v1.0.0') RETURNING id
                """, Long.class, projectId, name, 2, "http://tp-local.test");
        return eid;
    }

    private long profile(long projectId, String name, Map<String, Object> payload) {
        return envProfileService.create(projectId,
                new AppautoEnvProfileInput(name, "MP_DEVTOOLS", payload, "测试档案"));
    }

    private String[] rowOf(long id) {
        return jdbcTemplate.query(
                "SELECT automation_target, appauto_engine, appauto_env_profile_id::text "
                        + "FROM tp_regression_suite WHERE id = ?",
                rs -> rs.next()
                        ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}
                        : null, id);
    }

    @Test
    void createAppTargetNormalizesAndPersists() {
        long pid = createProject("SAT1");
        long c1 = createCase(pid, "登录回归");
        long env1 = createEnv(pid, "测试环境");
        SuiteInfo created = suiteService.create(1L, new SuiteCreateInput(pid, "APP回归集",
                List.of(c1), List.of(env1), List.of(), null, 1, " APP ", " mobilewright ", null));
        assertEquals("app", created.automationTarget());
        assertEquals("MOBILEWRIGHT", created.appautoEngine());
        assertNull(created.appautoEnvProfileId());
        String[] row = rowOf(created.id());
        assertEquals("app", row[0]);
        assertEquals("MOBILEWRIGHT", row[1]);
        assertNull(row[2], "app 目标档案可空（adb 设备走派发租约）");
    }

    @Test
    void legacyConstructorDefaultsToNullColumns() {
        long pid = createProject("SAT2");
        long c1 = createCase(pid, "登录回归");
        long env1 = createEnv(pid, "测试环境");
        SuiteInfo created = suiteService.create(1L, new SuiteCreateInput(pid, "旧姿回归集",
                List.of(c1), List.of(env1), List.of(), null, 1));
        assertNull(created.automationTarget());
        assertNull(created.appautoEngine());
        assertNull(created.appautoEnvProfileId());
        assertNull(rowOf(created.id())[0], "未挂=NULL 列按 web 语义");
    }

    @Test
    void webOrNullTargetCarryingEngineRejected() {
        long pid = createProject("SAT3");
        long c1 = createCase(pid, "登录回归");
        long env1 = createEnv(pid, "测试环境");
        ServiceException e = assertThrows(ServiceException.class,
                () -> suiteService.create(1L, new SuiteCreateInput(pid, "网页带引擎",
                        List.of(c1), List.of(env1), List.of(), null, 1, "web", "MOBILEWRIGHT", null)));
        assertTrue(e.getMessage().contains("web") || e.getMessage().contains("网页"),
                "应点名 web 不能挂引擎: " + e.getMessage());

        ServiceException bad = assertThrows(ServiceException.class,
                () -> suiteService.create(1L, new SuiteCreateInput(pid, "非法目标",
                        List.of(c1), List.of(env1), List.of(), null, 1, "mobile", null, null)));
        assertTrue(bad.getMessage().contains("web/app/mp"),
                "非法值域应点名 web/app/mp: " + bad.getMessage());
    }

    @Test
    void mpTargetProfileGates() {
        long pid = createProject("SAT4");
        long other = createProject("SAT4B");
        long c1 = createCase(pid, "小程序回归");
        long env1 = createEnv(pid, "测试环境");
        long good = profile(pid, "档案甲", Map.of("appid", "wx1", "projectPath", "/repo/mp"));

        ServiceException noProfile = assertThrows(ServiceException.class,
                () -> suiteService.create(1L, new SuiteCreateInput(pid, "MP缺档案",
                        List.of(c1), List.of(env1), List.of(), null, 1, "mp", "MINIUM", null)));
        assertTrue(noProfile.getMessage().contains("环境档案") || noProfile.getMessage().contains("projectPath"),
                "应点名环境档案: " + noProfile.getMessage());

        long noPath = profile(pid, "档案无路径", Map.of("appid", "wx2"));
        ServiceException badPath = assertThrows(ServiceException.class,
                () -> suiteService.create(1L, new SuiteCreateInput(pid, "MP档案缺路径",
                        List.of(c1), List.of(env1), List.of(), null, 1, "mp", "MINIUM", noPath)));
        assertTrue(badPath.getMessage().contains("projectPath"),
                "应点名 projectPath: " + badPath.getMessage());

        long foreign = profile(other, "他项目档案", Map.of("projectPath", "/x"));
        ServiceException cross = assertThrows(ServiceException.class,
                () -> suiteService.create(1L, new SuiteCreateInput(pid, "MP跨项目档案",
                        List.of(c1), List.of(env1), List.of(), null, 1, "mp", "MINIUM", foreign)));
        assertTrue(cross.getMessage().contains("不属于当前项目"),
                "应点名项目归属: " + cross.getMessage());

        SuiteInfo created = suiteService.create(1L, new SuiteCreateInput(pid, "MP合规",
                List.of(c1), List.of(env1), List.of(), null, 1, "mp", "MINIUM", good));
        assertEquals("mp", created.automationTarget());
        assertEquals(String.valueOf(good), rowOf(created.id())[2]);
    }

    @Test
    void platformMismatchEngineRejected() {
        long pid = createProject("SAT5");
        long c1 = createCase(pid, "登录回归");
        long env1 = createEnv(pid, "测试环境");
        ServiceException e = assertThrows(ServiceException.class,
                () -> suiteService.create(1L, new SuiteCreateInput(pid, "错引擎",
                        List.of(c1), List.of(env1), List.of(), null, 1, "app", "MINIUM", null)));
        assertTrue(e.getMessage().contains("不支持") && e.getMessage().contains("ANDROID"),
                "应点名平台面违例: " + e.getMessage());
    }

    @Test
    void apiTargetSuiteMountsWithoutEngineLegally() {
        long pid = createProject("SAT7B");
        long c1 = createCase(pid, "登录回归");
        long env1 = createEnv(pid, "测试环境");
        // Suite side shares the same domain gate (validateCaseTargetFields): api must be
        // accepted after V247 widens tp_regression_suite CHECK as well (both tables, no
        // half-widened domain = the V219 discipline).
        SuiteInfo created = suiteService.create(1L, new SuiteCreateInput(pid, "API回归集",
                List.of(c1), List.of(env1), List.of(), null, 1, " api ", null, null));
        assertEquals("api", created.automationTarget(), "api suite target should normalize and persist");
        assertEquals("api", rowOf(created.id())[0], "DB row of tp_regression_suite should hold api");
    }

    @Test
    void updateMergeDemoteAndClearSemantics() {
        long pid = createProject("SAT6");
        long c1 = createCase(pid, "登录回归");
        long env1 = createEnv(pid, "测试环境");
        long prof = profile(pid, "更新档案", Map.of("projectPath", "/repo/mp2"));
        SuiteInfo created = suiteService.create(1L, new SuiteCreateInput(pid, "更新链",
                List.of(c1), List.of(env1), List.of(), null, 1, "app", "MOBILEWRIGHT", null));

        SuiteInfo kept = suiteService.update(1L, created.id(),
                new SuiteUpdateInput("更新链", List.of(c1), List.of(env1), List.of(), null, 1,
                        null, null, null));
        assertEquals("app", kept.automationTarget());
        assertEquals("MOBILEWRIGHT", kept.appautoEngine());

        SuiteInfo demoted = suiteService.update(1L, created.id(),
                new SuiteUpdateInput("更新链", List.of(c1), List.of(env1), List.of(), null, 1,
                        "web", null, null));
        assertEquals("web", demoted.automationTarget());
        assertNull(demoted.appautoEngine());
        assertNull(rowOf(created.id())[1], "降 web 后引擎列应清空");

        SuiteInfo mp = suiteService.update(1L, created.id(),
                new SuiteUpdateInput("更新链", List.of(c1), List.of(env1), List.of(), null, 1,
                        "mp", "minium", prof));
        assertEquals("mp", mp.automationTarget());
        assertEquals("MINIUM", mp.appautoEngine());
        assertEquals(String.valueOf(prof), rowOf(created.id())[2]);

        SuiteInfo autoCleared = suiteService.update(1L, created.id(),
                new SuiteUpdateInput("更新链", List.of(c1), List.of(env1), List.of(), null, 1,
                        null, "", null));
        assertEquals("mp", autoCleared.automationTarget(), "目标保持 mp");
        assertNull(autoCleared.appautoEngine(), "引擎清空=自动（NULL 列）");
    }
}
