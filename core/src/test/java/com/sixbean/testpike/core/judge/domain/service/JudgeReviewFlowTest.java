package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewView;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RecordView;
import com.sixbean.testpike.core.judge.api.dto.RubricDimension;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
import com.sixbean.testpike.core.judge.api.dto.RunCreateInput;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeReviewFlowTest {

    @Autowired
    private JudgeReviewService judgeReviewService;

    @Autowired
    private JudgeRecordService judgeRecordService;

    @Autowired
    private JudgeRunService judgeRunService;

    @Autowired
    private JudgeRubricService judgeRubricService;

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_review WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j4%')");
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j4%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j4%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j4s.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j4k.%'");
    }

    
    private RecordView fixtureRecord(String tag) {
        RubricView rubric = judgeRubricService.save(new RubricSaveInput("t278j4k." + tag,
                "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of()),
                        new RubricDimension("完整性", "覆盖全不全", 0.5, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));
        EvalSetVersionView set = judgeSampleService.publish("t278j4s." + tag, List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0)), 9101L);
        RunView run = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j4-" + tag, null, 9101L));
        Long sid = judgeSampleService.listSamples(set.setName(), set.version()).stream()
                .map(SampleView::id).findFirst().orElseThrow();
        return judgeRecordService.saveScore(new RecordInput(run.id(), sid, 100,
                List.of("json-valid 通过"), Map.of("相关性", 90.0, "完整性", 60.0),
                Map.of("相关性", "切题（引用锚 offset 12-30）"), 300L, 1000));
    }

    @Test
    void 总改判_流水落库_原分快照_人工终判位回流() {
        RecordView rec = fixtureRecord("overall");
        assertEquals(85, rec.finalScore(), "夹具基线（回归护栏）");

        JudgeReviewView review = judgeReviewService.recordReview(new JudgeReviewInput(
                rec.id(), 9102L, null, 60, "LLM 高估了完整性，人工核过缺分支覆盖"));

        assertNotNull(review.id());
        assertEquals(rec.runId(), review.runId(), "run 归属从记录派生，禁调用方自报错位");
        assertEquals(85, review.orgScore(), "总改判原分快照=合成层 final_score");
        assertNull(review.dimension(), "总改判=dimension 空");
        assertEquals(60, review.newScore());
        assertEquals("LLM 高估了完整性，人工核过缺分支覆盖", review.reason());

        Integer manual = jdbcTemplate.queryForObject(
                "SELECT manual_final_score FROM tp_judge_record WHERE id = ?",
                Integer.class, rec.id());
        assertEquals(60, manual, "改判回流=manual_final_score 覆盖（κ 统计的人工真值）");

        judgeReviewService.recordReview(new JudgeReviewInput(rec.id(), 9103L, null, 70, "复核上调"));
        Integer latest = jdbcTemplate.queryForObject(
                "SELECT manual_final_score FROM tp_judge_record WHERE id = ?",
                Integer.class, rec.id());
        assertEquals(70, latest, "最新改判覆盖终判位");
        assertEquals(2, judgeReviewService.listReviews(rec.id()).size(), "改判流水 append-only 全保留");
    }

    @Test
    void 维度级改判_原分取该维快照_不动人工总判位() {
        RecordView rec = fixtureRecord("dim");

        JudgeReviewView review = judgeReviewService.recordReview(new JudgeReviewInput(
                rec.id(), 9102L, "相关性", 30, "引用锚对不上原文"));

        assertEquals("相关性", review.dimension());
        assertEquals(90, review.orgScore(), "维度原分快照=llm_scores 该维分四舍五入");
        assertEquals(rec.runId(), review.runId());

        Integer manual = jdbcTemplate.queryForObject(
                "SELECT manual_final_score FROM tp_judge_record WHERE id = ?",
                Integer.class, rec.id());
        assertNull(manual, "维度级改判不动总判位（人工终判只认总分口径）");
    }

    @Test
    void 越界缺参记录不存在_failClosed人话拒() {
        RecordView rec = fixtureRecord("bad");

        ValidationException e1 = assertThrows(ValidationException.class,
                () -> judgeReviewService.recordReview(new JudgeReviewInput(rec.id(), 9102L, null, 150, null)));
        assertTrue(e1.getMessage().contains("0~100"), e1.getMessage());

        assertThrows(ValidationException.class,
                () -> judgeReviewService.recordReview(new JudgeReviewInput(rec.id(), null, null, 60, null)),
                "改判人必填（κ 归因到人）");

        assertThrows(ValidationException.class,
                () -> judgeReviewService.recordReview(new JudgeReviewInput(999999999L, 9102L, null, 60, null)),
                "记录不存在=fail-closed");

        assertThrows(ValidationException.class,
                () -> judgeReviewService.recordReview(new JudgeReviewInput(null, 9102L, null, 60, null)),
                "缺记录 id 人话点名");
    }

    @Test
    void 改判流水回读_最新在前() {
        RecordView rec = fixtureRecord("order");
        judgeReviewService.recordReview(new JudgeReviewInput(rec.id(), 9102L, null, 55, "初判"));
        judgeReviewService.recordReview(new JudgeReviewInput(rec.id(), 9102L, null, 65, "复核"));

        List<JudgeReviewView> rows = judgeReviewService.listReviews(rec.id());
        assertEquals(2, rows.size());
        assertEquals(65, rows.get(0).newScore(), "倒序=最新在前（人审工作台展示口径）");
        assertEquals(55, rows.get(1).newScore());
    }
}
