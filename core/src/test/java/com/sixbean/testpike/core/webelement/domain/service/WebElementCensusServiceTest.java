package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusEntry;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WE-S6 census service door : the survey folds the
 * project's persisted web scripts through the single-source census scanner,
 * so the ranking rides real tp_script rows instead of in-memory documents.
 * Scope contract: only end_type='web' cases of the requested project are
 * counted; soft-deleted cases, still-generating scripts (status 3) and
 * scripts without a payload stay out and never break the survey.
 * <p>Real dev database, no mocks. Fixtures create their own project and
 * rows, cleanup deletes only what this class created.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class WebElementCensusServiceTest {

    private static final String LOGIN_PAGE = "http://census.test/login";

    @Autowired
    private WebElementCensusService censusService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long projectId;

    @BeforeEach
    void setUp() {
        String code = "cen" + System.nanoTime();
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "存量普查测试项目-" + code, code);
        projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        // Fixtures live under this throw-away project only; sweep by project.
        jdbc.update("DELETE FROM tp_script WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbc.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** Seed one case+script pair with an explicit end type and script status. */
    private void seed(String endType, int scriptStatus, String actionJson) {
        Long caseId = jdbc.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title, end_type) "
                        + "VALUES (?, ?, '普查夹具用例', ?) RETURNING id",
                Long.class, "TC-CEN-" + System.nanoTime(), projectId, endType);
        if (actionJson == null) {
            jdbc.update("INSERT INTO tp_script (case_id, schema_version, "
                    + "action_json, status) VALUES (?, 'v2', NULL::jsonb, ?)",
                    caseId, scriptStatus);
        } else {
            jdbc.update("INSERT INTO tp_script (case_id, schema_version, "
                    + "action_json, status) VALUES (?, 'v2', ?::jsonb, ?)",
                    caseId, actionJson, scriptStatus);
        }
    }

    private static String scriptWith(String css) {
        return "{\"endType\":\"web\",\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\""
                + LOGIN_PAGE + "\"}}],\"steps\":[{\"action\":\"click\",\"target\":{\"css\":\""
                + css + "\"}}]}";
    }

    @Test
    void censusSurveysOnlyTheProjectsWebScripts() {
        String shared = "#login-btn-" + System.nanoTime();
        seed("web", 1, scriptWith(shared));
        seed("web", 1, scriptWith(shared));
        String mobileOnly = "#mobile-only-" + System.nanoTime();
        seed("mobile", 1, scriptWith(mobileOnly));

        WebElementCensusReport report = censusService.censusProject(projectId);

        assertEquals(2, report.scannedScripts(),
                "mobile 用例与生成中载荷不参与存量普查");
        assertEquals(1, report.entries().size());
        WebElementCensusEntry top = report.entries().get(0);
        assertEquals(2, top.scriptCount());
        assertEquals(shared, top.anchors().get("css"));
        assertEquals(LOGIN_PAGE, top.pageKey());
    }

    @Test
    void generatingAndEmptyPayloadScriptsStayOutOfCensus() {
        String ready = "#ready-" + System.nanoTime();
        seed("web", 1, scriptWith(ready));
        seed("web", 3, scriptWith("#generating-" + System.nanoTime()));
        seed("web", 1, null);

        WebElementCensusReport report = censusService.censusProject(projectId);

        assertEquals(1, report.scannedScripts(),
                "生成中与无载荷脚本须被排除，普查账如实只数可迁移载荷");
        assertEquals(1, report.entries().size());
        assertEquals(ready, report.entries().get(0).anchors().get("css"));
    }

    @Test
    void payloadWithoutStepsIsCountedButProducesNoEntry() {
        seed("web", 1, "{\"endType\":\"web\",\"setup\":[],\"steps\":[]}");

        WebElementCensusReport report = censusService.censusProject(projectId);

        assertEquals(1, report.scannedScripts());
        assertTrue(report.entries().isEmpty());
        assertEquals(0, report.unattributedSteps());
    }

    @Test
    void blankProjectIsRejectedBeforeAnyQuery() {
        assertThrows(ServiceException.class, () -> censusService.censusProject(null));
        ServiceException missing = assertThrows(ServiceException.class,
                () -> censusService.censusProject(0L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, missing.getCode());
        assertTrue(missing.getMessage().contains("项目"),
                "拒止文案须点名缺的是项目： " + missing.getMessage());
        assertTrue(missing.getMessage().contains("页面元素库"),
                "refusal must name the page hosting the census trigger: " + missing.getMessage());
        assertTrue(missing.getMessage().contains("批量迁移入库"),
                "refusal must name a reachable trigger button: " + missing.getMessage());
    }

    @Test
    void foreignProjectScriptsNeverLeakIntoTheSurvey() {
        String marker = "#foreign-" + System.nanoTime();
        seed("web", 1, scriptWith("#mine-" + System.nanoTime()));
        // A second project owning a web script with its own marker.
        String otherCode = "cenf" + System.nanoTime();
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "邻项目-" + otherCode, otherCode);
        Long otherProject = jdbc.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, otherCode);
        try {
            Long caseId = jdbc.queryForObject(
                    "INSERT INTO tp_test_case (case_no, project_id, title) "
                            + "VALUES (?, ?, '邻项目用例') RETURNING id",
                    Long.class, "TC-CENF-" + System.nanoTime(), otherProject);
            Long scriptId = jdbc.queryForObject(
                    "INSERT INTO tp_script (case_id, schema_version, action_json) "
                            + "VALUES (?, 'v2', ?::jsonb) RETURNING id",
                    Long.class, caseId, scriptWith(marker));
            try {
                WebElementCensusReport report = censusService.censusProject(projectId);
                assertTrue(report.entries().stream()
                                .noneMatch(e -> marker.equals(e.anchors().get("css"))),
                        "跨项目切片必须硬隔离");
            } finally {
                jdbc.update("DELETE FROM tp_script WHERE id = ?", scriptId);
                jdbc.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
            }
        } finally {
            jdbc.update("DELETE FROM tp_project WHERE id = ?", otherProject);
        }
    }
}
