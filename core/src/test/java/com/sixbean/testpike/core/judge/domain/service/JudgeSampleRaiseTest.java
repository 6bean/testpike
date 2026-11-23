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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeSampleRaiseTest {

    private static final String FIXTURE_SET_LIKE = "t278j6s.%";
    private static final String FIXTURE_CASE_ID = "99060%";

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE ?", FIXTURE_SET_LIKE);
        jdbcTemplate.update("DELETE FROM tp_training_sample WHERE CAST(case_id AS TEXT) LIKE ?",
                FIXTURE_CASE_ID);
    }

    private long insertTrainingSample(long caseId, String sampleType, String sampleJson) {
        jdbcTemplate.update("INSERT INTO tp_training_sample (case_id, sample_type, sample_json, "
                        + "source_version) VALUES (?, ?, ?::jsonb, ?)",
                caseId, sampleType, sampleJson, 3);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_training_sample WHERE case_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, caseId);
        return id;
    }

    
    private String rejectJson(String title, String remark) {
        return "{\"review\":{\"remark\":\"" + remark + "\",\"feedbackTypes\":[\"覆盖不全\"]},"
                + "\"case\":{\"id\":990601,\"caseNo\":\"TC-D1-001\",\"title\":\"" + title + "\","
                + "\"precondition\":\"已登录\",\"expectedResult\":\"列表分页正确\","
                + "\"stepsText\":\"1. 打开列表页\"}}";
    }

    
    private String diffJson(String afterValue) {
        return "{\"caseId\":990601,\"fromVersion\":2,\"toVersion\":3,\"truncated\":false,"
                + "\"changes\":[{\"field\":\"expectedResult\",\"label\":\"预期结果\","
                + "\"before\":\"旧值\",\"after\":\"" + afterValue + "\"}]}";
    }

    @Test
    void 驳回样本提升_input含标题与驳回意见_expected空_溯源入参考件() {
        long sid = insertTrainingSample(990601, "驳回", rejectJson("登录后可见列表", "步骤缺少前置校验"));

        EvalSetVersionView pub = judgeSampleService.raiseFromTrainingSamples(
                "t278j6s.reject", List.of(sid), 9201L);

        assertEquals("t278j6s.reject", pub.setName());
        assertEquals(1, pub.version());
        assertEquals(1, pub.sampleCount());
        List<SampleView> rows = judgeSampleService.listSamples("t278j6s.reject", 1);
        assertEquals(1, rows.size());
        SampleView s = rows.get(0);
        assertEquals("training-raise", s.source());
        assertTrue(s.input().contains("登录后可见列表"), "input 必含用例标题（被评上下文）");
        assertTrue(s.input().contains("步骤缺少前置校验"), "input 必含驳回意见（为什么被评）");
        assertTrue(s.input().contains("列表分页正确"), "input 含用例快照字段原文");
        assertEquals(null, s.expected(), "无修改差异配对=不编造 expected");
        assertTrue(s.referenceDocs().stream().anyMatch(r -> r.contains("TC-D1-001")),
                "rejected 产物原文与用例号入参考件=提升溯源锚");
        assertEquals(9201L, s.createdBy());
    }

    @Test
    void 驳回样本配对最新修改差异_expected取改动后值() {
        long rejectId = insertTrainingSample(990601, "驳回", rejectJson("订单导出", "字段缺列"));
        insertTrainingSample(990601, "修改差异", diffJson("旧改动"));
        long newerDiff = insertTrainingSample(990601, "修改差异", diffJson("新改动为准"));

        judgeSampleService.raiseFromTrainingSamples("t278j6s.pair", List.of(rejectId), 9201L);

        SampleView s = judgeSampleService.listSamples("t278j6s.pair", 1).get(0);
        assertTrue(s.expected().contains("新改动为准"), "配对取同 case 最新修改差异行");
        assertTrue(s.expected().contains("预期结果"), "expected 带字段人话标签");
    }

    @Test
    void 修改差异样本单独提升_diff入expected_input用同行快照() {
        long diffId = insertTrainingSample(990602, "修改差异",
                "{\"caseId\":990602,\"changes\":[{\"field\":\"title\",\"label\":\"标题\","
                        + "\"before\":\"甲\",\"after\":\"乙\"}]}");

        judgeSampleService.raiseFromTrainingSamples("t278j6s.diffonly", List.of(diffId), 9201L);

        SampleView s = judgeSampleService.listSamples("t278j6s.diffonly", 1).get(0);
        assertTrue(s.expected().contains("标题：甲 → 乙"), "diff 行字段级改动人话化入 expected");
        assertTrue(s.input().contains("990602"), "input 带 case 溯源号");
        assertEquals("training-raise", s.source());
    }

    @Test
    void 悬空样本ID_整请求人话拒且不落版本行() {
        long real = insertTrainingSample(990603, "驳回", rejectJson("可提升的用例", "意见"));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromTrainingSamples(
                        "t278j6s.missing", List.of(real, 999999999L), 9201L));
        assertTrue(ex.getMessage().contains("999999999"), "点名缺失样本 ID");
        Integer left = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_sample WHERE set_name = 't278j6s.missing'",
                Integer.class);
        assertEquals(0, left, "拒单=零落行（fail-closed 禁半截版本）");
    }

    @Test
    void 空ID列表_人话拒() {
        assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromTrainingSamples(
                        "t278j6s.empty", List.of(), 9201L));
    }

    @Test
    void 同名集再提升_版本递增新锚() {
        long s1 = insertTrainingSample(990604, "驳回", rejectJson("第一版用例", "意见一"));
        EvalSetVersionView first = judgeSampleService.raiseFromTrainingSamples(
                "t278j6s.bump", List.of(s1), 9201L);
        long s2 = insertTrainingSample(990605, "驳回", rejectJson("第二版用例", "意见二"));
        EvalSetVersionView second = judgeSampleService.raiseFromTrainingSamples(
                "t278j6s.bump", List.of(s1, s2), 9201L);

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertTrue(!first.snapshotDigest().equals(second.snapshotDigest()), "内容变=锚变");
        assertEquals(2, second.sampleCount());
    }
}
