package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoHealthGroupView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoSupportView;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoSupportServiceTest {

    private static final int S = 3;
    private static final int F = 4;
    private static final int T = 5;

    @Autowired
    private AppautoSupportService supportService;
    @Autowired
    private AppAutoDispatchSupport dispatchSupport;
    @Autowired
    private ClusterComponentService componentService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private String nodeProbe;

    @BeforeEach
    void setUp() {
        String code = "sup" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "支持度测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        nodeProbe = "sup-test-" + System.nanoTime();
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE component_family = 'engine-runtime.node'");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_cluster_component WHERE node_id LIKE 'sup-test-%'");
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    
    private void insertRun(String engineJson, int status, int dayOffset,
                           Integer errorType, Integer aiClass) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json,
                                          error_type, ai_failure_class, finished_at)
                VALUES (?, 990001, ?, 6, ?::text, ?, ?,
                        CURRENT_TIMESTAMP + (? || ' days')::interval)
                """, projectId, status,
                "{\"kind\":\"app-auto-run\",\"runId\":\"r\",\"steps\":[]"
                        + (engineJson == null ? "" : "," + engineJson) + "}",
                errorType, aiClass, String.valueOf(dayOffset));
    }

    private static String mwPlatform(String platform) {
        return platform == null
                ? "\"engine\":{\"code\":\"MOBILEWRIGHT\"}"
                : "\"engine\":{\"code\":\"MOBILEWRIGHT\",\"platform\":\"" + platform + "\"}";
    }

    private AppautoSupportView viewOf(List<AppautoSupportView> views, String engineCode) {
        return views.stream()
                .filter(v -> engineCode == null ? v.engineCode() == null
                        : engineCode.equals(v.engineCode()))
                .findFirst().orElse(null);
    }

    @Test
    void version_dim_reuses_tricolor_rules_from_ledger() {
        componentService.upsert(nodeProbe, "engine-runtime.node", "22.12",
                "manual", ">=22.12", "22.12");
        AppautoSupportView view = viewOf(supportService.support(projectId), "MOBILEWRIGHT");
        assertNotNull(view);
        assertEquals("GREEN", view.versionState(), "台账满足区间+现值==基准应判绿: " + view.versionReason());
    }

    @Test
    void version_dim_gray_when_no_ledger_row() {
        AppautoSupportView view = viewOf(supportService.support(projectId), "MOBILEWRIGHT");
        assertNotNull(view);
        assertEquals("UNKNOWN", view.versionState(), "无台账行=未上报诚实灰，禁猜绿");
        assertTrue(view.versionReason().contains("台账"),
                "灰理由须人话点名缺证（无台账上报）: " + view.versionReason());
    }

    @Test
    void health_groups_by_engine_platform_with_rate_and_dist() {
        insertRun(mwPlatform("ANDROID"), S, -1, null, null);
        insertRun(mwPlatform("ANDROID"), F, -1, 1, 2);
        insertRun(mwPlatform("ANDROID"), T, -1, 3, null);
        insertRun(mwPlatform(null), S, -1, null, null);
        insertRun(null, F, -1, 2, null);
        List<AppautoSupportView> views = supportService.support(projectId);
        AppautoSupportView mw = viewOf(views, "MOBILEWRIGHT");
        assertNotNull(mw);
        AppautoHealthGroupView android = mw.healthGroups().stream()
                .filter(g -> "ANDROID".equals(g.platform())).findFirst().orElse(null);
        assertNotNull(android, "ANDROID 组必须成行: " + mw.healthGroups());
        assertEquals(3, android.samples());
        assertEquals(1, android.passCount());
        assertEquals(0.3333, android.passRate(), 0.0001);
        assertEquals(1, android.errorTypeDist().get("产品缺陷"));
        assertEquals(1, android.errorTypeDist().get("环境问题"));
        assertNull(android.errorTypeDist().get("未分类"), "两条失败行 error_type 均已给出=无未分类桶");
        assertEquals(1, android.aiFailureDist().get("AI定位错误"));
        assertEquals(1, android.aiFailureDist().get("未归类"));
        assertTrue(mw.healthGroups().stream().anyMatch(g -> g.platform() == null),
                "平台缺位=未知平台组诚实归并");
        AppautoSupportView unknown = viewOf(views, null);
        assertNotNull(unknown, "信封缺 engine 的行必须归「未知引擎」视图，禁丢禁猜");
        assertEquals(1, unknown.healthSamples());
    }

    @Test
    void window_excludes_rows_beyond_k8_days() {
        insertRun(mwPlatform("ANDROID"), S, -1, null, null);
        insertRun(mwPlatform("ANDROID"), F, -30, 1, null);
        AppautoSupportView mw = viewOf(supportService.support(projectId), "MOBILEWRIGHT");
        assertNotNull(mw);
        assertEquals(1, mw.healthSamples(), "30 天前的行不入近 N 天健康维");
        assertTrue(mw.caliberNote().contains("7"), "口径注脚须写明窗口: " + mw.caliberNote());
    }

    @Test
    void below_line_yields_yellow_with_attribution_advice() {
        insertRun(mwPlatform("ANDROID"), F, -1, 1, null);
        insertRun(mwPlatform("ANDROID"), F, -1, 1, null);
        insertRun(mwPlatform("ANDROID"), F, -1, 1, null);
        AppautoSupportView mw = viewOf(supportService.support(projectId), "MOBILEWRIGHT");
        assertNotNull(mw);
        assertEquals("YELLOW", mw.healthState(), "通过率 0% 跌破线=黄");
        assertTrue(mw.conclusion().contains("建议先看归因再升级"),
                "两维一句话结论须含跌破线人话建议: " + mw.conclusion());
    }

    @Test
    void envelope_platform_parse_hole_is_honest_null() {
        assertEquals("ANDROID",
                dispatchSupport.envelopePlatformCode(
                        "{\"kind\":\"app-auto-run\",\"engine\":{\"code\":\"X\",\"platform\":\"ANDROID\"}}"));
        assertNull(dispatchSupport.envelopePlatformCode(
                "{\"kind\":\"app-auto-run\",\"engine\":{\"code\":\"X\"}}"), "缺 platform=诚实 null");
        assertNull(dispatchSupport.envelopePlatformCode(
                "{\"steps\":[]}"), "非 app 信封=null");
        assertNull(dispatchSupport.envelopePlatformCode("{bad json"), "脏 JSON=null 不炸");
    }

    @Test
    void caliber_note_declares_status_spectrum() {
        insertRun(mwPlatform("IOS"), S, -1, null, null);
        AppautoSupportView mw = viewOf(supportService.support(projectId), "MOBILEWRIGHT");
        assertNotNull(mw);
        assertNotNull(mw.caliberNote());
        assertTrue(mw.caliberNote().contains("task_type=6") && mw.caliberNote().contains("终态"),
                "口径注脚=验收硬项（数字程序化实算+口径摊开）: " + mw.caliberNote());
        Map<String, Integer> dist = mw.healthGroups().get(0).errorTypeDist();
        assertTrue(dist.isEmpty() || dist.get("未分类") == 1, "通过行不进失败归因谱: " + dist);
    }
}
