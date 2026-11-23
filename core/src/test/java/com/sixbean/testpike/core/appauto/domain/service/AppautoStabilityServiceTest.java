package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityScanResult;
import com.sixbean.testpike.core.appauto.api.dto.AppautoStabilityView;
import com.sixbean.testpike.core.cases.domain.component.FlakyRuleCalculator;
import com.sixbean.testpike.core.cases.domain.component.FlakyVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoStabilityServiceTest {

    private static final int S = 3;
    private static final int F = 4;
    private static final int T = 5;

    @Autowired
    private AppautoStabilityService stabilityService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private final FlakyRuleCalculator referenceCalculator = new FlakyRuleCalculator();

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "stb" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "稳定性分测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(AppautoConfigReader.KEY_FLAKY_SENTINEL);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    
    private void insertAppRun(long caseId, Long deviceId, int status, int minuteIndex) {
        String leaseRef = deviceId == null
                ? "\"leaseRef\":\"legacy-string\""
                : "\"leaseRef\":{\"deviceId\":" + deviceId + ",\"serial\":\"s" + deviceId + "\"}";
        String actionJson = "{\"kind\":\"app-auto-run\",\"runId\":\"r" + minuteIndex
                + "\"," + leaseRef + ",\"engine\":{\"code\":\"MOBILEWRIGHT\"},\"steps\":[]}";
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json,
                                          finished_at)
                VALUES (?, ?, ?, 6, ?::text, TIMESTAMP '2026-10-01 00:00:00'
                        + (? || ' minutes')::interval)
                """, projectId, caseId, status, actionJson, String.valueOf(minuteIndex));
    }

    private AppautoStabilityView rowOf(List<AppautoStabilityView> rows, long deviceId) {
        return rows.stream().filter(r -> Long.valueOf(deviceId).equals(r.deviceId()))
                .findFirst().orElseThrow();
    }

    @Test
    void 翻转判定与FlakyRuleCalculator一字同源() {
        long caseId = 9101L;
        List<Integer> statuses = List.of(S, F, S, F, S);
        for (int i = 0; i < statuses.size(); i++) {
            insertAppRun(caseId, 7001L, statuses.get(i), i);
        }
        AppautoStabilityView row = rowOf(
                stabilityService.stabilityRows(projectId, caseId), 7001L);
        FlakyVerdict reference = referenceCalculator.evaluate(statuses, false);
        assertEquals(reference.samples(), row.samples());
        assertEquals(reference.flips(), row.flips());
        assertEquals(reference.flaky(), row.flaky());
        assertTrue(row.flaky(), "3 样 2 翻=命中 flaky 判定线");
    }

    @Test
    void 设备维隔离同用例异设备各算各窗() {
        long caseId = 9102L;
        List<Integer> flipping = List.of(S, F, S, F, S);
        for (int i = 0; i < flipping.size(); i++) {
            insertAppRun(caseId, 7002L, flipping.get(i), i);
        }
        for (int i = 0; i < 4; i++) {
            insertAppRun(caseId, 7003L, S, i);
        }
        List<AppautoStabilityView> rows = stabilityService.stabilityRows(projectId, caseId);
        assertEquals(2, rows.size(), "设备 A/B 各一行=用例×设备维度聚合");
        assertTrue(rowOf(rows, 7002L).flaky());
        assertFalse(rowOf(rows, 7003L).flaky(), "设备 B 全绿窗口不得被设备 A 翻转串样");
        assertEquals(1.0, rowOf(rows, 7003L).stabilityScore(), 1e-9);
    }

    @Test
    void 稳定性分为窗口通过占比程序化实算() {
        long caseId = 9103L;
        List<Integer> statuses = List.of(S, S, F, S, T, F, S, S, F, S);
        for (int i = 0; i < statuses.size(); i++) {
            insertAppRun(caseId, 7004L, statuses.get(i), i);
        }
        AppautoStabilityView row = rowOf(
                stabilityService.stabilityRows(projectId, caseId), 7004L);
        assertEquals(10, row.samples());
        assertEquals(0.6, row.stabilityScore(), 1e-9);
        assertTrue(row.flaky());
    }

    @Test
    void 窗口只收APP终态行非本链形态零混入() {
        long caseId = 9104L;
        insertAppRun(caseId, 7005L, S, 0);
        insertAppRun(caseId, 7005L, F, 1);
        insertAppRun(caseId, 7005L, S, 2);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, ?, 2, 6, '{"kind":"app-auto-run","leaseRef":{"deviceId":7005}}')
                """, projectId, caseId);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json)
                VALUES (?, ?, 4, 1, '{}')
                """, projectId, caseId);
        insertAppRun(0L, 7005L, F, 3);
        List<AppautoStabilityView> rows = stabilityService.stabilityRows(projectId, caseId);
        assertEquals(1, rows.size());
        AppautoStabilityView row = rows.get(0);
        assertEquals(3, row.samples(), "仅 APP 终态行入窗（非终态/异类型/哨兵零混入）");
    }

    @Test
    void 旧字符串leaseRef归设备未知组不猜设备() {
        long caseId = 9105L;
        insertAppRun(caseId, null, S, 0);
        insertAppRun(caseId, null, F, 1);
        insertAppRun(caseId, null, S, 2);
        List<AppautoStabilityView> rows = stabilityService.stabilityRows(projectId, caseId);
        assertEquals(1, rows.size(), "旧字符串形态=设备未知，多行归并同组（诚实缺位禁按 serial 猜）");
        assertNull(rows.get(0).deviceId());
        assertEquals(3, rows.get(0).samples());
    }

    @Test
    void 跨项目执行历史不入本口() {
        long caseId = 9106L;
        insertAppRun(caseId, 7006L, S, 0);
        insertAppRun(caseId, 7006L, F, 1);
        insertAppRun(caseId, 7006L, S, 2);
        String otherCode = "stb2" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "他项目-" + otherCode, otherCode);
        long otherProjectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, otherCode);
        try {
            jdbcTemplate.update("""
                    INSERT INTO tp_exec_task (project_id, case_id, status, task_type, action_json,
                                              finished_at)
                    VALUES (?, ?, 4, 6,
                            '{"kind":"app-auto-run","leaseRef":{"deviceId":9999}}',
                            TIMESTAMP '2026-10-01 01:00:00')
                    """, otherProjectId, caseId);
            List<AppautoStabilityView> rows = stabilityService.stabilityRows(projectId, caseId);
            assertEquals(1, rows.size());
            assertEquals(7006L, rows.get(0).deviceId(), "他项目行不混入本口（H1 项目数据口径）");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", otherProjectId);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", otherProjectId);
        }
    }

    @Test
    void 扫描关态零外呼零残留只读聚合() {
        long caseId = 9107L;
        List<Integer> statuses = List.of(S, F, S, F, S);
        for (int i = 0; i < statuses.size(); i++) {
            insertAppRun(caseId, 7007L, statuses.get(i), i);
        }
        long caseRowsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case", Long.class);
        long sampleRowsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_ai_sample", Long.class);
        // seed default flipped to '1' by the AI default-on batch; force off-state explicitly for this closed-state scenario
        configCacheHolder.put(AppautoConfigReader.KEY_FLAKY_SENTINEL, "0");
        AppautoStabilityScanResult result = stabilityService.scan();
        long caseRowsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_test_case", Long.class);
        long sampleRowsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_appauto_ai_sample", Long.class);
        assertTrue(result.groupsScanned() > 0, "扫描面=全项目用例×设备窗口");
        assertFalse(result.aiSuggestionEnabled(),
                "ai.appauto.flaky-sentinel 键默认关（V158）=AI 建议翻译注记位关，零模型外呼");
        assertEquals(caseRowsBefore, caseRowsAfter, "扫描零残留：Web 用例表计数不动（APP 分不写 Web flaky 位）");
        assertEquals(sampleRowsBefore, sampleRowsAfter, "扫描零残留：样本库计数不动");
    }
}
