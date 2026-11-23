package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoCaseFeatureProfile;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdviceView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeAdvisorLlmRequest;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceUpgradeAdvisorClient;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest(classes = CoreApplication.class)
class AppautoUpgradeAdvisorServiceTest {

    @Autowired
    private AppautoUpgradeAdvisorService advisorService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private AiServiceUpgradeAdvisorClient aiServiceUpgradeAdvisorClient;

    private long projectId;
    private String manifestVersion;
    private String caseNo;

    @BeforeEach
    void setUp() {
        String code = "adv" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "升级顾问测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        manifestVersion = "v-adv-" + System.nanoTime();
        caseNo = "TC-ADV-" + System.nanoTime();
        configCacheHolder.put(AppautoConfigReader.KEY_UPGRADE_ADVISOR, "1");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(AppautoConfigReader.KEY_UPGRADE_ADVISOR);
        jdbcTemplate.update("DELETE FROM tp_release_manifest WHERE version = ?", manifestVersion);
        jdbcTemplate.update("DELETE FROM tp_appauto_ai_sample WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void seedManifest(String breakingChangesJson) {
        jdbcTemplate.update("INSERT INTO tp_release_manifest (version, breaking_changes) "
                + "VALUES (?, ?::jsonb)", manifestVersion, breakingChangesJson);
    }

    
    private void seedCaseWithScript(String actionJson) {
        Long caseId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title) VALUES (?, ?, ?) "
                        + "RETURNING id", Long.class, caseNo, projectId, "顾问画像用例");
        jdbcTemplate.update("INSERT INTO tp_script (case_id, schema_version, action_json) "
                + "VALUES (?, 'v2', ?::jsonb)", caseId, actionJson);
    }

    private AppautoUpgradeAdviceRequest request() {
        return new AppautoUpgradeAdviceRequest(projectId, manifestVersion);
    }

    @Test
    void switchOffRejectsHumanWordsAndNeverCallsModel() {
        configCacheHolder.put(AppautoConfigReader.KEY_UPGRADE_ADVISOR, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        ServiceException ex = assertThrows(ServiceException.class,
                () -> advisorService.advise(request()));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("未开启：请在「系统管理 → 系统配置」搜索") && ex.getMessage().contains("ai.appauto.upgrade-advisor"), "关态文案须人话说明开关未开：" + ex.getMessage());
        verifyNoInteractions(aiServiceUpgradeAdvisorClient);
    }

    @Test
    void parameterGatesRejectBeforeAnyModelCall() {
        ServiceException noProject = assertThrows(ServiceException.class,
                () -> advisorService.advise(new AppautoUpgradeAdviceRequest(null, manifestVersion)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noProject.getCode());
        ServiceException noVersion = assertThrows(ServiceException.class,
                () -> advisorService.advise(new AppautoUpgradeAdviceRequest(projectId, "  ")));
        assertTrue(noVersion.getMessage().contains("版本"), "缺版本文案须人话点名发版本号：" + noVersion.getMessage());
        seedManifest("[]");
        ServiceException unknown = assertThrows(ServiceException.class,
                () -> advisorService.advise(new AppautoUpgradeAdviceRequest(projectId, "v-不存在-000")));
        assertTrue(unknown.getMessage().contains("发布清单"), "清单缺行须人话指引去登记：" + unknown.getMessage());
        verifyNoInteractions(aiServiceUpgradeAdvisorClient);
    }

    @Test
    void sendsComputedFeatureProfileAndManifestBreakingChanges() {
        seedManifest("[\"大版本变更 app.swipe 语义\", \"下架 image-matching 能力\"]");
        seedCaseWithScript("""
                {"steps":[
                  {"action":"app.launch","target":{"pkg":"com.demo"}},
                  {"action":"app.tap","target":{"locator":{"id":"btn"}}},
                  {"action":"app.tap","target":{"locator":{"id":"btn2"}}},
                  {"action":"app.swipe","webview":true},
                  {"action":"not-an-object-should-skip"}
                ]}""");
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, 990001, 3, 6,
                        '{"kind":"app-auto-run","runId":"r","steps":[],"engine":{"code":"MOBILEWRIGHT"}}'::text)
                """, projectId);
        Long sampleCase = jdbcTemplate.queryForObject(
                "SELECT case_id FROM tp_script WHERE case_id IN "
                        + "(SELECT id FROM tp_test_case WHERE project_id = ?) LIMIT 1",
                Long.class, projectId);
        jdbcTemplate.update("""
                INSERT INTO tp_appauto_ai_sample (project_id, case_id, step_no, anchor,
                                                  hit_source, review_result)
                VALUES (?, ?, 1, 'btn', 'vision', 'confirmed')
                """, projectId, sampleCase);
        when(aiServiceUpgradeAdvisorClient.advise(any())).thenReturn(Map.of(
                "risks", List.of("2 条用例依赖 app.tap，大版本变更 swipe 语义影响面低"),
                "summary", "总体低风险"));

        AppautoUpgradeAdviceView view = advisorService.advise(request());

        ArgumentCaptor<AppautoUpgradeAdvisorLlmRequest> captor =
                ArgumentCaptor.forClass(AppautoUpgradeAdvisorLlmRequest.class);
        verify(aiServiceUpgradeAdvisorClient).advise(captor.capture());
        AppautoUpgradeAdvisorLlmRequest sent = captor.getValue();
        assertEquals(manifestVersion, sent.manifestVersion());
        assertEquals(projectId, sent.projectId());
        assertEquals(List.of("大版本变更 app.swipe 语义", "下架 image-matching 能力"),
                sent.breakingChanges(), "破坏性变更原样透传清单行禁二次加工");
        AppautoCaseFeatureProfile profile = sent.profile();
        assertEquals(1, profile.scriptCases(), "画像用例数=有现行脚本的用例数");
        assertEquals(2, profile.verbCounts().get("app.tap"), "动词计数程序化实算");
        assertEquals(1, profile.verbCounts().get("app.launch"));
        assertEquals(1, profile.verbCounts().get("app.swipe"));
        assertEquals(1, profile.webviewSteps(), "webview 步旗计数");
        assertEquals(1, profile.aiSampleCases(), "AI 功能特征=样本库用例数");
        assertEquals(1, profile.engineUsage().get("MOBILEWRIGHT"), "引擎使用经信封投影单点");

        assertEquals(List.of("2 条用例依赖 app.tap，大版本变更 swipe 语义影响面低"), view.risks());
        assertEquals("总体低风险", view.summary());
        assertEquals(2, view.breakingChanges().size(), "清单破坏性变更随行回读");
        assertTrue(view.conclusion().contains("1 个用例"), "一句话摘要=程序化实算数字：" + view.conclusion());
    }

    @Test
    void malformedLlmAnswerRejectedWithoutTrust() {
        seedManifest("[]");
        when(aiServiceUpgradeAdvisorClient.advise(any())).thenReturn(Map.of("note", "忘了 risks 键"));
        ServiceException missing = assertThrows(ServiceException.class,
                () -> advisorService.advise(request()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, missing.getCode());
        when(aiServiceUpgradeAdvisorClient.advise(any()))
                .thenReturn(Map.of("risks", "不是数组"));
        assertThrows(ServiceException.class, () -> advisorService.advise(request()));
    }
}
