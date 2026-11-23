package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPortabilityInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoPortabilityServiceTest {

    @Autowired
    private AppautoPortabilityService portabilityService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long projectId;
    private Long caseId;

    
    private static final String FULL_IR_STEPS = """
            {"steps":[
              {"stepNo":1,"action":"app.launch","pkg":"com.demo.app"},
              {"stepNo":2,"action":"app.tap","target":{"accessibilityId":"login_btn"}},
              {"stepNo":3,"action":"app.assertText","text":"欢迎"},
              {"stepNo":4,"action":"app.close"}]}
            """;

    
    private static final String WEB_MIXED_STEPS = """
            {"steps":[{"stepNo":1,"action":"goto","target":{"url":"http://x"}},
              {"stepNo":2,"action":"app.tap"}]}
            """;

 
    private static final String WEBVIEW_FLAGGED_STEPS = """
            {"steps":[
              {"stepNo":1,"action":"app.launch","pkg":"com.demo.hybrid"},
              {"stepNo":2,"action":"app.tap","webview":true,"target":{"testId":"web_btn"}}]}
            """;

    
    private static final String WEBVIEW_STRING_FLAG_STEPS = """
            {"steps":[
              {"stepNo":1,"action":"app.tap","webview":"true","target":{"testId":"web_btn"}}]}
            """;

    @BeforeEach
    void seedProjectAndClean() {
        projectId = jdbcTemplate.query(
                "SELECT id FROM tp_project ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (projectId == null) {
            projectId = jdbcTemplate.queryForObject(
                    "INSERT INTO tp_project (name, code) VALUES ('预检测试项目','PORTTEST') RETURNING id",
                    Long.class);
        }
        deleteFixtures();
    }

    @AfterEach
    void restoreAndClean() {
        jdbcTemplate.update("UPDATE tp_appauto_engine SET status = 0 WHERE code = 'HYPIUM'");
        deleteFixtures();
    }

    private void deleteFixtures() {
        jdbcTemplate.update("""
                DELETE FROM tp_script WHERE case_id IN
                    (SELECT id FROM tp_test_case WHERE project_id = ? AND case_no LIKE 'TC-PORT-%')
                """, projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_env_profile WHERE project_id = ? AND name LIKE 'port-%'",
                projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ? AND case_no LIKE 'TC-PORT-%'",
                projectId);
    }

    
    private long createAppCase(String caseNo, String engine, String stepsJson, Long profileId) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_test_case (case_no, project_id, title, status, source, created_by,
                                          automation_target, appauto_engine, appauto_env_profile_id)
                VALUES (?, ?, '迁移预检用例', 1, 1, 1, 'app', ?, ?) RETURNING id
                """, Long.class, caseNo, projectId, engine, profileId);
        if (stepsJson != null) {
            jdbcTemplate.update("INSERT INTO tp_script (case_id, schema_version, action_json) VALUES (?, 'v2', ?::jsonb)",
                    id, stepsJson);
        }
        return id;
    }

    private long createFlutterProfile() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_appauto_env_profile (project_id, name, profile_kind, payload)
                VALUES (?, 'port-flutter', 'ANDROID_SDK', '{"appFramework":"FLUTTER"}'::jsonb)
                RETURNING id
                """, Long.class, projectId);
    }

    @Test
    void all_verbs_covered_and_no_flag_shrink_is_runnable() {
        caseId = createAppCase("TC-PORT-00001", "APPIUM", FULL_IR_STEPS, null);
        AppautoPortabilityInfo info = portabilityService.precheck(projectId, caseId, "XCUITEST");
        assertEquals("RUNNABLE", info.verdict());
        assertTrue(info.items().isEmpty(), "XCUITEST 基线动词全在位+特性旗只增不减=零差集: " + info.items());
        assertEquals("XCUITEST", info.engineCode());
        assertNotNull(info.caseTitle());
    }

    @Test
    void verb_gap_yields_rewrite_with_step_naming() {
        caseId = createAppCase("TC-PORT-00002", "MOBILEWRIGHT", FULL_IR_STEPS, null);
        jdbcTemplate.update("UPDATE tp_appauto_engine SET status = 1 WHERE code = 'HYPIUM'");
        AppautoPortabilityInfo info = portabilityService.precheck(projectId, caseId, "HYPIUM");
        assertEquals("REWRITE", info.verdict());
        assertTrue(info.items().size() >= 4, "四个动词步逐一改名: " + info.items());
        String joined = String.join("；", info.items());
        assertTrue(joined.contains("app.launch") && joined.contains("app.tap"),
                "改写清单须点名动词: " + joined);
        assertTrue(joined.contains("1") && joined.contains("2"), "改写清单须点名步号: " + joined);
    }

    @Test
    void flutter_framework_on_engine_without_flag_is_rewrite() {
        long profileId = createFlutterProfile();
        caseId = createAppCase("TC-PORT-00003", "APPIUM", FULL_IR_STEPS, profileId);
        AppautoPortabilityInfo info = portabilityService.precheck(projectId, caseId, "MOBILEWRIGHT");
        assertEquals("REWRITE", info.verdict());
        assertTrue(info.items().stream().anyMatch(s -> s.contains("FLUTTER")),
                "框架差集必须人话点名: " + info.items());
    }

    @Test
    void flag_shrink_without_verb_gap_is_degraded() {
        caseId = createAppCase("TC-PORT-00004", "MOBILEWRIGHT", FULL_IR_STEPS, null);
        AppautoPortabilityInfo info = portabilityService.precheck(projectId, caseId, "MAESTRO");
        assertEquals("DEGRADED", info.verdict());
        assertTrue(info.items().stream().anyMatch(s -> s.contains("webview-hybrid")),
                "特性旗收窄须逐旗点名: " + info.items());
    }

    @Test
    void webview_flagged_step_on_engine_without_cap_is_rewrite() {
        caseId = createAppCase("TC-PORT-00010", "MOBILEWRIGHT", WEBVIEW_FLAGGED_STEPS, null);
        AppautoPortabilityInfo info = portabilityService.precheck(projectId, caseId, "MAESTRO");
        assertEquals("REWRITE", info.verdict());
        String joined = String.join("；", info.items());
        assertTrue(joined.contains("WebView") || joined.contains("webview"),
                "落区差集须人话点名 WebView: " + joined);
        assertTrue(joined.contains("第 2 步"), "落区差集须点名步号: " + joined);
    }

    @Test
    void webview_flagged_step_on_capable_engine_stays_runnable() {
        caseId = createAppCase("TC-PORT-00011", "APPIUM", WEBVIEW_FLAGGED_STEPS, null);
        AppautoPortabilityInfo info = portabilityService.precheck(projectId, caseId, "XCUITEST");
        assertEquals("RUNNABLE", info.verdict());
        assertTrue(info.items().isEmpty(), "有位引擎带旗步零差集: " + info.items());
    }

    @Test
    void non_boolean_webview_flag_does_not_trigger_hard_gate() {
        caseId = createAppCase("TC-PORT-00012", "MOBILEWRIGHT", WEBVIEW_STRING_FLAG_STEPS, null);
        AppautoPortabilityInfo info = portabilityService.precheck(projectId, caseId, "MAESTRO");
        assertEquals("DEGRADED", info.verdict());
    }

    @Test
    void disabled_engine_is_rejected_in_plain_words() {
        caseId = createAppCase("TC-PORT-00005", "MOBILEWRIGHT", FULL_IR_STEPS, null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> portabilityService.precheck(projectId, caseId, "HYPIUM"));
        assertTrue(ex.getMessage().contains("停用") || ex.getMessage().contains("下架"),
                "停用引擎人话点名: " + ex.getMessage());
    }

    @Test
    void case_without_app_steps_is_rejected_in_plain_words() {
        caseId = createAppCase("TC-PORT-00006", "MOBILEWRIGHT", WEB_MIXED_STEPS, null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> portabilityService.precheck(projectId, caseId, "APPIUM"));
        assertTrue(ex.getMessage().contains("app."), "未编排文案点名 IR 动词: " + ex.getMessage());
    }

    @Test
    void foreign_project_case_is_rejected() {
        caseId = createAppCase("TC-PORT-00007", "MOBILEWRIGHT", FULL_IR_STEPS, null);
        Long other = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code) VALUES ('预检越权项目','PORTOTHER') RETURNING id",
                Long.class);
        try {
            assertThrows(ServiceException.class,
                    () -> portabilityService.precheck(other, caseId, "APPIUM"));
        } finally {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", other);
        }
    }

    @Test
    void blank_target_engine_is_rejected_in_plain_words() {
        caseId = createAppCase("TC-PORT-00009", "MOBILEWRIGHT", FULL_IR_STEPS, null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> portabilityService.precheck(projectId, caseId, "  "));
        assertTrue(ex.getMessage().contains("引擎"), "缺目标引擎人话点名: " + ex.getMessage());
    }
}
