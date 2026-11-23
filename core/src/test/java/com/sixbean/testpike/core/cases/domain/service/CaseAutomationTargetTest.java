package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEnvProfileInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEnvProfileService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseAutomationTargetTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private AppautoEnvProfileService envProfileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> automationTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — only the rows
        // reachable from this suite's tracked fixture projects are removed,
        // case-keyed children included (see SharedDbFixtureSweep).
        ProjectFixtureRig.sweep(jdbcTemplate, automationTrackedProjects);
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                automationTrackedProjects, "项目" + code, code);
    }

    private CaseCreateInput input(long projectId, String title, String target, String engine, Long profileId) {
        return new CaseCreateInput(projectId, title, null, null, 1, 2, "web",
                1, null, 1, null, List.of(new CaseStepInput(1, "打开应用", 0)),
                null, null, target, engine, profileId);
    }

    
    private long profile(long projectId, String name, Map<String, Object> payload) {
        return envProfileService.create(projectId,
                new AppautoEnvProfileInput(name, "MP_DEVTOOLS", payload, "测试档案"));
    }

    private String[] rowOf(long id) {
        return jdbcTemplate.query(
                "SELECT automation_target, appauto_engine, appauto_env_profile_id::text "
                        + "FROM tp_test_case WHERE id = ?",
                rs -> rs.next()
                        ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}
                        : null, id);
    }

    @Test
    void defaultTargetIsNullWebSemantics() {
        long pid = createProject("CATT1");
        CaseInfo created = caseService.create(1L, input(pid, "缺省目标用例", null, null, null));
        assertEquals(null, created.automationTarget());
        assertEquals(null, created.appautoEngine());
        String[] row = rowOf(created.id());
        assertEquals(null, row[0], "DB 行 automation_target 应为 NULL（未挂=web 语义）");
        assertEquals(null, row[2]);
    }

    @Test
    void createAppTargetWithEngineEchoAndDbRowWithNormalization() {
        long pid = createProject("CATT2");
        CaseInfo created = caseService.create(1L, input(pid, "APP目标用例", " APP ", " mobilewright ", null));
        assertEquals("app", created.automationTarget());
        assertEquals("MOBILEWRIGHT", created.appautoEngine());
        String[] row = rowOf(created.id());
        assertEquals("app", row[0]);
        assertEquals("MOBILEWRIGHT", row[1]);
        assertEquals(null, row[2], "app 目标档案可空（adb 设备走派发租约）");
    }

    @Test
    void createMpTargetNeedsProjectPathProfileAndEngineFace() {
        long pid = createProject("CATT3");
        long prof = profile(pid, "小程序档案A", Map.of("appid", "wx1", "projectPath", "/repo/mp"));
        CaseInfo created = caseService.create(1L, input(pid, "MP目标用例", "mp", "MINIUM", prof));
        assertEquals("mp", created.automationTarget());
        assertEquals("MINIUM", created.appautoEngine());
        assertEquals(String.valueOf(prof), rowOf(created.id())[2]);

        ServiceException noProfile = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "MP缺档案", "mp", "MINIUM", null)));
        assertTrue(noProfile.getMessage().contains("projectPath") || noProfile.getMessage().contains("环境档案"),
                "应点名环境档案缺失: " + noProfile.getMessage());

        long badProf = profile(pid, "小程序档案无路径", Map.of("appid", "wx2"));
        ServiceException noPath = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "MP档案缺路径", "mp", "MINIUM", badProf)));
        assertTrue(noPath.getMessage().contains("projectPath"), "应点名 projectPath: " + noPath.getMessage());

        ServiceException face = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "MP错引擎", "mp", "MOBILEWRIGHT", prof)));
        assertTrue(face.getMessage().contains("MOBILEWRIGHT") || face.getMessage().contains("平台"),
                "应点名引擎/平台面: " + face.getMessage());
    }

    @Test
    void appTargetRejectsMpEngineFace() {
        long pid = createProject("CATT4");
        ServiceException e = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "APP错引擎", "app", "MINIUM", null)));
        assertTrue(e.getMessage().contains("MINIUM") || e.getMessage().contains("平台"),
                "应点名引擎或平台面: " + e.getMessage());
        ServiceException unknown = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "未知引擎", "app", "NOT_EXIST_ENGINE", null)));
        assertTrue(unknown.getMessage().contains("NOT_EXIST_ENGINE"), "应点名编码: " + unknown.getMessage());
    }

    @Test
    void profileMustBelongToProjectAndWebRejectsEngine() {
        long pid = createProject("CATT5");
        long otherPid = createProject("CATT5B");
        long foreignProfile = profile(otherPid, "他项目档案", Map.of("projectPath", "/x"));
        ServiceException cross = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "跨项目档案", "mp", "MINIUM", foreignProfile)));
        assertTrue(cross.getMessage().contains("不属于当前项目") || cross.getMessage().contains("环境档案"),
                "应人话点名档案归属: " + cross.getMessage());

        ServiceException webEngine = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "WEB带引擎", "web", "MOBILEWRIGHT", null)));
        assertTrue(webEngine.getMessage().contains("引擎") || webEngine.getMessage().contains("web"),
                "应点名引擎必空: " + webEngine.getMessage());

        ServiceException bad = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "非法目标", "mobile", null, null)));
        assertTrue(bad.getMessage().contains("web/app/mp") || bad.getMessage().contains("mobile"),
                "应点名值域与当前值: " + bad.getMessage());
    }

    @Test
    void harmonyTargetMountsWithEngineOrAutoLegally() {
        long pid = createProject("CATT8");
        CaseInfo created = caseService.create(1L, input(pid, "鸿蒙目标用例", " Harmony ", " hmnextauto ", null));
        assertEquals("harmony", created.automationTarget());
        assertEquals("HMNEXTAUTO", created.appautoEngine());
        assertEquals("harmony", rowOf(created.id())[0], "DB 行应落 harmony");
        CaseInfo auto = caseService.create(1L, input(pid, "鸿蒙自动用例", "harmony", null, null));
        assertEquals("harmony", auto.automationTarget());
        assertEquals(null, auto.appautoEngine(), "引擎缺位=自动语义（NULL 列合法）");
        ServiceException face = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "鸿蒙错引擎", "harmony", "MINIUM", null)));
        assertTrue(face.getMessage().contains("HARMONY_NEXT") || face.getMessage().contains("平台"),
                "应点名鸿蒙平台面: " + face.getMessage());
    }

    @Test
    void appOrMpTargetWithoutEngineDefaultsToAuto() {
        long pid = createProject("CATT9");
        long prof = profile(pid, "自动档档案", Map.of("appid", "wx9", "projectPath", "/repo/mp9"));
        CaseInfo appAuto = caseService.create(1L, input(pid, "APP自动用例", "app", null, null));
        assertEquals("app", appAuto.automationTarget());
        assertEquals(null, appAuto.appautoEngine());
        assertEquals(null, rowOf(appAuto.id())[1], "DB 行引擎列应 NULL=自动");
        CaseInfo mpAuto = caseService.create(1L, input(pid, "MP自动用例", "mp", null, prof));
        assertEquals("mp", mpAuto.automationTarget());
        assertEquals(null, mpAuto.appautoEngine());
        ServiceException webEngine = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "WEB带引擎自动", "web", "MOBILEWRIGHT", null)));
        assertTrue(webEngine.getMessage().contains("引擎") || webEngine.getMessage().contains("web"),
                "web 必空闸应仍人话拒: " + webEngine.getMessage());
        ServiceException bad = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "非法目标扩", "mobile", null, null)));
        assertTrue(bad.getMessage().contains("harmony"), "值域提示应含 harmony: " + bad.getMessage());
    }

    @Test
    void apiTargetIsLegalNormalizedAndPersisted() {
        long pid = createProject("CATT10");
        // Promotion chain requires automation_target value domain to include api (V247,
        // both tables). Normalization: upper-case with blanks -> stored as api; engine and
        // profile stay optional because an API case runs http.request steps and the engine
        // is a runtime "auto" default (dispatch falls back to the K8 default engine, the
        // existing B11 webdriver-inline posture).
        CaseInfo created = caseService.create(1L, input(pid, "升格API用例", " API ", null, null));
        assertEquals("api", created.automationTarget(), "api target should pass the domain gate and normalize");
        assertEquals(null, created.appautoEngine());
        assertEquals("api", rowOf(created.id())[0], "DB row should hold api (V247 widened domain)");
        // Invalid values must name the widened five-value domain (including api) in plain words
        ServiceException bad = assertThrows(ServiceException.class,
                () -> caseService.create(1L, input(pid, "非法目标API扩", "mobile", null, null)));
        assertTrue(bad.getMessage().contains("api"), "domain hint should contain api: " + bad.getMessage());
    }

    @Test
    void updateChainFieldLevelClearSemanticsAndOptimisticLock() {
        long pid = createProject("CATT6");
        long prof = profile(pid, "更新用档案", Map.of("projectPath", "/repo/mp2"));
        CaseInfo created = caseService.create(1L, input(pid, "更新链用例", "mp", "MINIUM", prof));

        CaseInfo renamed = caseService.update(1L, created.id(),
                new CaseUpdateInput("更新链用例V2", null, null, null, null, null, null, null,
                        null, null, null, null, null), created.version());
        assertEquals("mp", renamed.automationTarget());
        assertEquals("MINIUM", renamed.appautoEngine());
        assertEquals(Long.valueOf(prof), renamed.appautoEnvProfileId());

        CaseInfo toWeb = caseService.update(1L, created.id(),
                new CaseUpdateInput(null, null, null, null, null, null, null, null,
                        null, null, "web", "", 0L), renamed.version());
        assertEquals("web", toWeb.automationTarget());
        assertEquals(null, toWeb.appautoEngine());
        String[] row = rowOf(created.id());
        assertEquals("web", row[0]);
        assertEquals(null, row[1]);
        assertEquals(null, row[2]);

        CaseInfo mpAgain = caseService.update(1L, created.id(),
                new CaseUpdateInput(null, null, null, null, null, null, null, null,
                        null, null, "mp", "MINIUM", prof), toWeb.version());
        CaseInfo autoCleared = caseService.update(1L, created.id(),
                new CaseUpdateInput(null, null, null, null, null, null, null, null,
                        null, null, null, "", null), mpAgain.version());
        assertEquals("mp", autoCleared.automationTarget(), "目标保持 mp");
        assertEquals(null, autoCleared.appautoEngine(), "引擎清空=自动合法（NULL 列）");

        assertThrows(OptimisticLockingException.class,
                () -> caseService.update(1L, created.id(),
                        new CaseUpdateInput(null, null, null, null, null, null, null, null,
                                null, null, "app", "MOBILEWRIGHT", 0L), 1));
    }

    @Test
    void cloneCarriesTargetSameProjectAndDropsCrossProject() {
        long pid = createProject("CATT7");
        long otherPid = createProject("CATT7B");
        long prof = profile(pid, "克隆档案", Map.of("projectPath", "/repo/mp3"));
        CaseInfo source = caseService.create(1L, input(pid, "克隆源用例", "mp", "MINIUM", prof));

        CaseInfo sameProject = caseService.clone(1L, source.id(), null);
        assertEquals("mp", sameProject.automationTarget(), "同项目克隆应携带目标");
        assertEquals("MINIUM", sameProject.appautoEngine(), "同项目克隆应携带引擎");
        assertEquals(Long.valueOf(prof), sameProject.appautoEnvProfileId(), "同项目克隆应携带档案");

        CaseInfo crossProject = caseService.clone(1L, source.id(), otherPid);
        assertEquals(null, crossProject.automationTarget(), "跨项目克隆应清挂（档案是项目资产）");
        assertEquals(null, crossProject.appautoEngine());
        assertEquals(null, crossProject.appautoEnvProfileId());
    }
}
