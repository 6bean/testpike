package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RecordView;
import com.sixbean.testpike.core.judge.api.dto.ReviewQueueView;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeReviewQueueTest {

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
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j4q%')");
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j4q%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j4q%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j4qs.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j4qk.%'");
    }

    
    private long fixtureRun(String tag) {
        RubricView rubric = judgeRubricService.save(new RubricSaveInput("t278j4qk." + tag,
                "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of()),
                        new RubricDimension("完整性", "覆盖全不全", 0.5, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));
        EvalSetVersionView set = judgeSampleService.publish("t278j4qs." + tag, List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0)), 9101L);
        RunView run = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j4q-" + tag, null, 9101L));
        return run.id();
    }

    private long fixtureSampleId(String tag) {
        return judgeSampleService.listSamples("t278j4qs." + tag, 1).stream()
                .map(SampleView::id).findFirst().orElseThrow();
    }

    private ReviewQueueView queueHit(long runId, long recordId) {
        List<ReviewQueueView> page = judgeReviewService.reviewQueue(runId, 0, 50);
        return page.stream().filter(v -> v.recordId().equals(recordId)).findFirst()
                .orElseThrow(() -> new AssertionError("记录未入人审队列: " + recordId));
    }

    @Test
    void 全维无据落分_入队且人话点名判分无据() {
        long runId = fixtureRun("noevid");
        RecordView rec = judgeRecordService.saveScore(new RecordInput(runId,
                fixtureSampleId("noevid"), 100, List.of("json-valid 通过"), Map.of(),
                Map.of("判分无据剔除维度", "相关性、完整性"), 200L, 800));

        ReviewQueueView hit = queueHit(runId, rec.id());
        assertTrue(hit.queueReason().contains("判分无据"), "理由须人话点名无据: " + hit.queueReason());
        assertTrue(hit.missingDimensions().containsAll(List.of("相关性", "完整性")),
                "无据维度全点名: " + hit.missingDimensions());
        assertEquals(rec.runId(), hit.runId());
    }

    @Test
    void 部分维度缺失_入队点名缺哪几维() {
        long runId = fixtureRun("dimmiss");
        RecordView rec = judgeRecordService.saveScore(new RecordInput(runId,
                fixtureSampleId("dimmiss"), 100, List.of(), Map.of("相关性", 90.0),
                Map.of("相关性", "切题（引用锚 offset 3-9）"), 200L, 500));

        ReviewQueueView hit = queueHit(runId, rec.id());
        assertEquals(List.of("完整性"), hit.missingDimensions(),
                "只差点名缺「完整性」一维，禁把打过的维也算缺");
        assertTrue(hit.queueReason().contains("维度缺失"), hit.queueReason());
    }

    @Test
    void 分全落完的干净记录_不入队() {
        long runId = fixtureRun("clean");
        RecordView rec = judgeRecordService.saveScore(new RecordInput(runId,
                fixtureSampleId("clean"), 100, List.of("json-valid 通过"),
                Map.of("相关性", 90.0, "完整性", 60.0),
                Map.of("相关性", "切题", "完整性", "覆盖全"), 300L, 1000));

        List<ReviewQueueView> page = judgeReviewService.reviewQueue(runId, 0, 50);
        assertFalse(page.stream().anyMatch(v -> v.recordId().equals(rec.id())),
                "正常样本禁灌进人审海（队列只收无据/缺维异常分）");
    }

    @Test
    void 记改判流水后_退出队列_处理即出队() {
        long runId = fixtureRun("handled");
        RecordView rec = judgeRecordService.saveScore(new RecordInput(runId,
                fixtureSampleId("handled"), 100, List.of(), Map.of(),
                Map.of("判分无据剔除维度", "相关性、完整性"), 200L, 800));
        assertNotNull(queueHit(runId, rec.id()), "先证在队（回归护栏）");

        judgeReviewService.recordReview(new JudgeReviewInput(rec.id(), 9102L, null, 55, "人工重评"));

        List<ReviewQueueView> page = judgeReviewService.reviewQueue(runId, 0, 50);
        assertFalse(page.stream().anyMatch(v -> v.recordId().equals(rec.id())),
                "已处理记录退出队列——人审工作量=未处理异常数，禁重复派单");
    }

    @Test
    void 最新在前分页_同run过滤页边界确定() {
        long runId = fixtureRun("paged");
        long sid = fixtureSampleId("paged");
        long r1 = fixtureRun("p1");
        long r2 = fixtureRun("p2");
        long r3 = fixtureRun("p3");
        judgeRecordService.saveScore(new RecordInput(runId, sid, 0, List.of(), Map.of(),
                Map.of("判分无据剔除维度", "相关性"), 10L, 10));
        judgeRecordService.saveScore(new RecordInput(r1, fixtureSampleId("p1"), 0, List.of(),
                Map.of(), Map.of("判分无据剔除维度", "相关性"), 10L, 10));
        judgeRecordService.saveScore(new RecordInput(r2, fixtureSampleId("p2"), 0, List.of(),
                Map.of(), Map.of("判分无据剔除维度", "相关性"), 10L, 10));
        judgeRecordService.saveScore(new RecordInput(r3, fixtureSampleId("p3"), 0, List.of(),
                Map.of(), Map.of("判分无据剔除维度", "相关性"), 10L, 10));

        List<Long> p0 = judgeReviewService.reviewQueue(null, 0, 2).stream()
                .map(ReviewQueueView::recordId).toList();
        List<Long> p1 = judgeReviewService.reviewQueue(null, 1, 2).stream()
                .map(ReviewQueueView::recordId).toList();
        assertEquals(2, p0.size(), "页大小 2=恰 2 条（夹具外存量残留行按倒序自然占位，不断条数下界）");
        assertTrue(java.util.Collections.disjoint(p0, p1), "页间不重叠: " + p0 + " vs " + p1);
        p0.forEach(id -> p1.forEach(other -> assertTrue(id >= other,
                "最新在前（created_at,id 双倒序）：" + id + " 不应早于 " + other)));
    }

    @Test
    void 页参越界与负页码_failclosed人话拒() {
        assertThrows(ValidationException.class, () -> judgeReviewService.reviewQueue(null, -1, 20),
                "负页码必须人话拒，禁静默回卷");
        assertThrows(ValidationException.class, () -> judgeReviewService.reviewQueue(null, 0, 0));
        assertThrows(ValidationException.class, () -> judgeReviewService.reviewQueue(null, 0, 501),
                "页大小越界拒——防全表扫拖垮人审台");
    }
}
