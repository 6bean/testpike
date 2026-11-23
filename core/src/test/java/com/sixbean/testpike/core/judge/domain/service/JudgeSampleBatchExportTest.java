package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeSampleBatchExportTest {

    private static final String FIXTURE_SET_LIKE = "t278j6b.%";
    private static final String FIXTURE_CASE_NO = "TC-T278J6B-%";

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private String ownerUsername;

    @BeforeEach
    void setUpFixture() {
        String code = "j6b" + Math.abs(System.nanoTime()) % 1000000000L;
        ownerUsername = "j6b-u-" + code;
        jdbcTemplate.update("INSERT INTO tp_user (username, password_hash, display_name, role, status)"
                + " VALUES (?, 'x', '评测导出夹具负责人', 4, 1)", ownerUsername);
        long ownerId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_user WHERE username = ?", Long.class, ownerUsername);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status, created_by) VALUES (?, ?, 1, ?)",
                "评测导出夹具-" + code, code, ownerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
        cleanFixtureRows();
    }

    @AfterEach
    void cleanFixture() {
        cleanFixtureRows();
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE username = ?", ownerUsername);
    }

    private void cleanFixtureRows() {
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE ?", FIXTURE_SET_LIKE);
        jdbcTemplate.update("DELETE FROM tp_exec_task WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE case_no LIKE ?)", FIXTURE_CASE_NO);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE case_no LIKE ?", FIXTURE_CASE_NO);
    }

    
    private long insertFinishedTask(String caseTitle, int status, Integer errorType,
                                    String errorDetail, String aiNote) {
        String caseNo = "TC-T278J6B-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_test_case (case_no, project_id, title, precondition, "
                        + "expected_result, steps_text, status) VALUES (?, ?, ?, '已登录', "
                        + "'列表分页正确', '1. 打开列表页; 2. 点击导出', 3)",
                caseNo, projectId, caseTitle);
        Long caseId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_test_case WHERE case_no = ?", Long.class, caseNo);
        jdbcTemplate.update("INSERT INTO tp_exec_task (project_id, case_id, status, action_json, "
                        + "error_type, error_detail, ai_failure_class, ai_failure_note) "
                        + "VALUES (?, ?, ?, '{\"actions\":[{\"type\":\"click\"}]}', ?, ?, 2, ?)",
                projectId, caseId, status, errorType, errorDetail, aiNote);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE case_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, caseId);
    }

    private List<SampleView> samplesOfSet(String setName, int version) {
        return judgeSampleService.listSamples(setName, version);
    }

    @Test
    void 失败任务导出_input含用例与失败人话_参考件带动作脚本_不编造expected() {
        long taskId = insertFinishedTask("导出按钮可见", 4, 2,
                "定位器未命中导出按钮", "AI 定位错误：选择器漂移");

        EvalSetVersionView pub = judgeSampleService.raiseFromBatchResults(
                "t278j6b.failed", List.of(taskId), 9202L);

        assertEquals(1, pub.version());
        assertEquals(1, pub.sampleCount());
        SampleView s = samplesOfSet("t278j6b.failed", 1).get(0);
        assertEquals("batch-export", s.source());
        assertTrue(s.input().contains("导出按钮可见"), "input 含用例标题（被评上下文）");
        assertTrue(s.input().contains("定位器未命中导出按钮"), "input 含失败详情（为什么被评）");
        assertTrue(s.input().contains("定位错误"), "input 含失败分类人话（error_type 复用缺陷域 label 单源）");
        assertTrue(s.input().contains("AI 定位错误：选择器漂移"), "input 含 AI 归因说明");
        assertTrue(s.input().contains("TC-T278J6B-"), "input 带用例编号溯源");
        assertNull(s.expected(), "执行结果不是标准答案=expected 留空禁编造");
        assertTrue(s.referenceDocs().stream().anyMatch(r -> r.contains("\"sourceTaskId\":" + taskId)
                && r.contains("click")), "被评动作脚本原文+任务号入参考件=导出溯源锚");
        assertEquals(9202L, s.createdBy());
    }

    @Test
    void 超时任务导出_input带超时人话() {
        long taskId = insertFinishedTask("长流程用例", 5, null,
                "执行超时：任务超过 10 分钟未回传结果", null);

        judgeSampleService.raiseFromBatchResults("t278j6b.timeout", List.of(taskId), 9202L);

        SampleView s = samplesOfSet("t278j6b.timeout", 1).get(0);
        assertTrue(s.input().contains("超时"), "input 含终态人话（超时）");
        assertTrue(s.input().contains("任务超过 10 分钟未回传结果"), "input 含超时详情");
    }

    @Test
    void 非失败终态任务_整请求人话拒且不落版本行() {
        long taskId = insertFinishedTask("成功跑通的用例", 3, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromBatchResults(
                        "t278j6b.success", List.of(taskId), 9202L));
        assertTrue(ex.getMessage().contains(String.valueOf(taskId)), "点名任务 ID");
        assertTrue(ex.getMessage().contains("成功"), "报出当前状态人话");
        Integer left = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_sample WHERE set_name = 't278j6b.success'",
                Integer.class);
        assertEquals(0, left, "拒单=零落行（fail-closed 禁半截版本）");
    }

    @Test
    void 悬空任务ID_整请求人话拒点名() {
        long real = insertFinishedTask("可导出的失败用例", 4, 1, "断言不通过", null);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromBatchResults(
                        "t278j6b.missing", List.of(real, 999999999L), 9202L));
        assertTrue(ex.getMessage().contains("999999999"), "点名缺失任务 ID");
        Integer left = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_sample WHERE set_name = 't278j6b.missing'",
                Integer.class);
        assertEquals(0, left, "整请求拒=不落半截");
    }

    @Test
    void 空任务列表_人话拒() {
        assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromBatchResults(
                        "t278j6b.empty", List.of(), 9202L));
    }

    @Test
    void 同名集再导出_版本递增新锚历史不可变() {
        long t1 = insertFinishedTask("第一版失败", 4, 1, "断言A不通过", null);
        EvalSetVersionView first = judgeSampleService.raiseFromBatchResults(
                "t278j6b.bump", List.of(t1), 9202L);
        long t2 = insertFinishedTask("第二版失败", 5, null, "超时B", null);
        EvalSetVersionView second = judgeSampleService.raiseFromBatchResults(
                "t278j6b.bump", List.of(t1, t2), 9202L);

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertTrue(!first.snapshotDigest().equals(second.snapshotDigest()), "内容变=锚变");
        assertEquals(2, second.sampleCount());
        assertEquals(1, samplesOfSet("t278j6b.bump", 1).size(), "历史版本行不可变（新版本=新行段）");
    }
}
