package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixCombo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleCandidate;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleLlmRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixSampleView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.infrastructure.remote.client.AiServiceMatrixSampleClient;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


@SpringBootTest(classes = CoreApplication.class)
class AppautoMatrixSampleServiceTest {

    private static final String APP_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}},"
                    + "{\"action\":\"app.tap\",\"target\":{\"resourceId\":\"com.demo:id/btn\"}}]}";
    private static final String WEB_ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}";

    @Autowired
    private AppautoMatrixSampleService matrixSampleService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoBean
    private AiServiceMatrixSampleClient aiServiceMatrixSampleClient;

    private long projectId;
    private String deviceName;

    @BeforeEach
    void setUp() {
        String code = "smp" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "矩阵抽样测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        deviceName = "smp-dev-" + System.nanoTime();
        configCacheHolder.put(AppautoConfigReader.KEY_MATRIX_SAMPLE, "1");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(AppautoConfigReader.KEY_MATRIX_SAMPLE);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id = ?)", projectId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    
    private long seedCase(String target, String actionJson) {
        Long id = jdbcTemplate.queryForObject("""
                        INSERT INTO tp_test_case (case_no, project_id, title, automation_target)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """, Long.class, "TC-SMP-" + System.nanoTime(), projectId, "抽样用例", target);
        if (actionJson != null) {
            jdbcTemplate.update("INSERT INTO tp_script (case_id, schema_version, action_json) "
                    + "VALUES (?, 'v2', ?::jsonb)", id, actionJson);
        }
        return id;
    }

    
    private void seedDevice(String model, String osVersion) {
        jdbcTemplate.update("INSERT INTO tp_appauto_device (project_id, name, serial, "
                        + "device_type, model, os_version) VALUES (?, ?, ?, 'EMULATOR', ?, ?)",
                projectId, deviceName, "SMP" + System.nanoTime(), model, osVersion);
    }

    private AppautoMatrixSampleRequest request(long caseId, Integer topN) {
        return new AppautoMatrixSampleRequest(projectId, caseId, topN);
    }

    @Test
    void switchOffRejectsHumanWordsAndNeverCallsModel() {
        long caseId = seedCase("app", APP_ACTION_JSON);
        configCacheHolder.put(AppautoConfigReader.KEY_MATRIX_SAMPLE, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        ServiceException ex = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(caseId, null)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("未开启：请在「系统管理 → 系统配置」搜索") && ex.getMessage().contains("ai.appauto.matrix-sample"), "关态文案须人话说明开关未开：" + ex.getMessage());
        verifyNoInteractions(aiServiceMatrixSampleClient);
    }

    @Test
    void parameterGatesRejectBeforeAnyModelCall() {
        ServiceException noProject = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(new AppautoMatrixSampleRequest(null, 1L, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noProject.getCode());

        long caseId = seedCase("app", APP_ACTION_JSON);
        ServiceException noCase = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(new AppautoMatrixSampleRequest(projectId, null, null)));
        assertTrue(noCase.getMessage().contains("用例"), "缺用例号文案须人话点名用例：" + noCase.getMessage());

        ServiceException unknown = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(990000999L, null)));
        assertTrue(unknown.getMessage().contains("当前项目"), "跨项目/不存在须人话拒：" + unknown.getMessage());

        long webCase = seedCase("web", WEB_ACTION_JSON);
        ServiceException noTarget = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(webCase, null)));
        assertTrue(noTarget.getMessage().contains("目标"), "未挂 App/小程序目标须人话指引：" + noTarget.getMessage());

        long bareCase = seedCase("app", null);
        ServiceException noSteps = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(bareCase, null)));
        assertTrue(noSteps.getMessage().contains("app."), "未编排 app.* 步骤须人话指引：" + noSteps.getMessage());

        long topNCase = seedCase("app", APP_ACTION_JSON);
        ServiceException tooBig = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(topNCase, 999)));
        assertTrue(tooBig.getMessage().contains("上限"), "Top-N 越扇出上限须人话点名上限：" + tooBig.getMessage());
        ServiceException nonPositive = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(topNCase, 0)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, nonPositive.getCode());

        verifyNoInteractions(aiServiceMatrixSampleClient);
    }

    @Test
    void sendsComputedCandidateFaceAndProfile() {
        long caseId = seedCase("app", APP_ACTION_JSON);
        seedDevice("Pixel 7", "13");
        seedDevice("Pixel 7", "14");
        jdbcTemplate.update("""
                        INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                        VALUES (?, ?, 3, 6,
                                '{"kind":"app-auto-run","runId":"r","steps":[],"engine":{"code":"MOBILEWRIGHT"}}'::text)
                        """, projectId, caseId);
        when(aiServiceMatrixSampleClient.sample(any())).thenAnswer(invocation -> {
            AppautoMatrixSampleLlmRequest sent = invocation.getArgument(0);
            AppautoMatrixSampleCandidate first = sent.candidates().get(0);
            return Map.of("recommendations", List.of(Map.of(
                            "engineCode", first.engineCode(),
                            "platformCode", first.platformCode(),
                            "deviceKindCode", first.deviceKindCode())),
                    "summary", "优先推荐成熟组合");
        });

        AppautoMatrixSampleView view = matrixSampleService.recommend(request(caseId, null));

        ArgumentCaptor<AppautoMatrixSampleLlmRequest> captor =
                ArgumentCaptor.forClass(AppautoMatrixSampleLlmRequest.class);
        verify(aiServiceMatrixSampleClient).sample(captor.capture());
        AppautoMatrixSampleLlmRequest sent = captor.getValue();
        assertEquals(projectId, sent.projectId());
        assertEquals(caseId, sent.caseId());
        assertEquals(sent.candidates().size(), sent.profile().candidateCount(),
                "候选面枚举数=下传候选行数（程序化实算单点）");
        assertTrue(sent.candidates().stream().anyMatch(c -> "ANDROID".equals(c.platformCode())
                        && "EMULATOR".equals(c.deviceKindCode()) && c.addressable()),
                "EMULATOR 档案在位=对应候选可寻址旗真");
        assertTrue(sent.candidates().stream().anyMatch(c -> "SIMULATOR".equals(c.deviceKindCode())
                        && !c.addressable()),
                "SIMULATOR 无映射谱=诚实不可寻址旗");
        assertEquals(2, sent.profile().installedModelAxes(), "机型现装面=model×os_version 去重实算");
        Double mobilewrightRate = sent.profile().engineRecentPassRate().get("MOBILEWRIGHT");
        assertNotNull(mobilewrightRate, "窗口历史通过率经信封投影单点实算");
        assertEquals(100.0, mobilewrightRate, 0.001, "终态样本通过率程序化实算");
        assertEquals(5, sent.profile().minutesPerTask(), "每任务分钟数=批次估时同源件单点");
        assertTrue(sent.topN() <= 12 && sent.topN() <= sent.candidates().size(),
                "默认 Top-N=min(候选面数, 扇出上限键)：" + sent.topN());

        assertEquals(1, view.recommendations().size(), "应答回读组合数");
        assertTrue(view.coverageMessage().contains("候选组合"), "覆盖率画像句人话回读：" + view.coverageMessage());
        assertTrue(view.costMessage().contains("排队"), "成本画像句人话回读：" + view.costMessage());
        assertEquals("优先推荐成熟组合", view.summary());
    }

    @Test
    void malformedLlmAnswerRejectedWithoutTrust() {
        long caseId = seedCase("app", APP_ACTION_JSON);
        when(aiServiceMatrixSampleClient.sample(any())).thenReturn(Map.of("note", "忘了 recommendations 键"));
        ServiceException missing = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(caseId, null)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, missing.getCode());

        when(aiServiceMatrixSampleClient.sample(any())).thenReturn(Map.of("recommendations", "不是数组"));
        ServiceException notArray = assertThrows(ServiceException.class,
                () -> matrixSampleService.recommend(request(caseId, null)));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, notArray.getCode());
    }

    @Test
    void combosOutsideDeclaredFaceDroppedAndNormalized() {
        long caseId = seedCase("app", APP_ACTION_JSON);
        when(aiServiceMatrixSampleClient.sample(any())).thenAnswer(invocation -> {
            AppautoMatrixSampleLlmRequest sent = invocation.getArgument(0);
            AppautoMatrixSampleCandidate first = sent.candidates().get(0);
            return Map.of("recommendations", List.of(
                    Map.of("engineCode", first.engineCode().toLowerCase(),
                            "platformCode", " " + first.platformCode().toLowerCase() + " ",
                            "deviceKindCode", first.deviceKindCode().toLowerCase()),
                    Map.of("engineCode", "NOTANENGINE",
                            "platformCode", "ANDROID",
                            "deviceKindCode", "EMULATOR")));
        });

        AppautoMatrixSampleView view = matrixSampleService.recommend(request(caseId, 2));

        assertEquals(1, view.recommendations().size(), "越声明面组合剔除不采信");
        assertTrue(sentCodes(view).stream().allMatch(c -> c.equals(c.toUpperCase())),
                "组合键大小写/空白归一：" + sentCodes(view));
    }

    private static List<String> sentCodes(AppautoMatrixSampleView view) {
        return view.recommendations().stream().map(AppautoMatrixCombo::engineCode).toList();
    }
}
