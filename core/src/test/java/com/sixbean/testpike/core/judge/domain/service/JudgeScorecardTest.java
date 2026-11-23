package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.RubricDimension;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
import com.sixbean.testpike.core.judge.api.dto.RunCreateInput;
import com.sixbean.testpike.core.judge.api.dto.RunScorecardView;
import com.sixbean.testpike.core.judge.api.dto.RunView;
import com.sixbean.testpike.core.judge.api.dto.SampleInput;
import com.sixbean.testpike.core.judge.api.dto.SampleView;
import com.sixbean.testpike.core.judge.api.dto.ScorecardTrendPointView;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeScorecardTest {

    @Autowired
    private JudgeScorecardService scorecardService;

    @Autowired
    private JudgeRunService judgeRunService;

    @Autowired
    private JudgeRubricService judgeRubricService;

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private JudgeRecordService judgeRecordService;

    @Autowired
    private JudgeReviewService judgeReviewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_review WHERE record_id IN "
                + "(SELECT r.id FROM tp_judge_record r JOIN tp_judge_run ru ON ru.id = r.run_id "
                + "WHERE ru.target_ref LIKE 't278j5%')");
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j5%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j5%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j5s.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j5k.%'");
        jdbcTemplate.update("DELETE FROM tp_llm_config WHERE name LIKE 't278j5m.%'");
    }


    @Test
    void 会话战绩卡_三列归因质量分与单条成本_未完成不入卡() {
        RubricView shared = fixtureRubric("sess");
        Long cfg = jdbcTemplate.queryForObject("INSERT INTO tp_llm_config "
                + "(name, provider, model_name) VALUES ('t278j5m.a', 'zhipu', 'glm-4.6') "
                + "RETURNING id", Long.class);
        EvalSetVersionView set = judgeSampleService.publish("t278j5s.sess", List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0),
                new SampleInput("需求B", "用例B", List.of(), "manual", 0),
                new SampleInput("需求C", "用例C", List.of(), "manual", 0)), 9101L);
        RunView a = judgeRunService.createRun(new RunCreateInput(shared.id(),
                set.setName(), set.version(), null, "pipeline", "t278j5-sessA", cfg, 9101L));
        RunView b = judgeRunService.createRun(new RunCreateInput(shared.id(),
                set.setName(), set.version(), null, "pipeline", "t278j5-sessB", null, 9101L));
        RunView c = judgeRunService.createRun(new RunCreateInput(shared.id(),
                set.setName(), set.version(), null, "external", "t278j5-sessC", cfg, 9101L));
        save(a, "需求A", 80, Map.of("相关性", 90.0, "完整性", 60.0), 1000, 200);
        save(a, "需求B", 60, Map.of("相关性", 40.0, "完整性", 40.0), 500, 100);
        save(b, "需求A", 90, Map.of("相关性", 50.0), 200, 60);
        judgeRunService.start(a.id());
        judgeRunService.finish(a.id(), true);
        judgeRunService.start(b.id());
        judgeRunService.finish(b.id(), true);
        judgeRunService.start(c.id());

        List<com.sixbean.testpike.core.judge.api.dto.GenerationSessionCardView> cards =
                scorecardService.sessionCards(shared.name(), "用例生成", 10);
        assertEquals(2, cards.size(), "仅已完成运行出卡（与趋势同取点口径）");
        var ca = cards.get(0);
        assertEquals(a.id(), ca.runId());
        assertEquals("pipeline", ca.targetType());
        assertEquals("t278j5-sessA", ca.targetRef(), "生成配置引用原样透传（降级归因位）");
        assertEquals("zhipu/glm-4.6", ca.judgeModel(), "模型名=llm_config join provider/model");
        assertEquals(2, ca.scored());
        assertEquals(63, ca.avgFinalScore(), "权威分均值 (77+48)/2=62.5 四舍五入");
        assertEquals(1, ca.passCount());
        assertEquals(0.5, ca.passRate(), 0.001);
        assertEquals(1500L, ca.tokensTotal());
        assertEquals(750L, ca.tokensPerSample(), "单条成本=1500 token/2 落分样本");
        assertEquals(150.0, ca.latencyAvgMs(), 0.001);
        var cb = cards.get(1);
        assertEquals(b.id(), cb.runId(), "正序=历史演进左到右");
        assertNull(cb.judgeModel(), "纯规则卡无模型位=null 不编造");
        assertEquals(66, cb.avgFinalScore());
        assertEquals(0, cb.passCount());
        assertEquals(0.0, cb.passRate(), 0.001, "有落分全未达线=0.0 非 null");
        assertEquals(200L, cb.tokensPerSample());
        assertTrue(!cb.finishedAt().isBlank(), "卡带终态时间可标轴");
    }

    @Test
    void 会话战绩卡_零落分单条成本不假装() {
        RunView run = fixtureRun("zerocost");
        judgeRunService.start(run.id());
        judgeRunService.finish(run.id(), true);
        List<com.sixbean.testpike.core.judge.api.dto.GenerationSessionCardView> cards =
                scorecardService.sessionCards("t278j5k.zerocost", "用例生成", 10);
        assertEquals(1, cards.size());
        var card = cards.get(0);
        assertEquals(0, card.scored());
        assertNull(card.avgFinalScore());
        assertNull(card.passRate());
        assertNull(card.tokensPerSample(), "零落分除零禁假 0 成本");
    }

    @Test
    void 会话战绩卡_参数闸与未知族空序列() {
        assertThrows(ValidationException.class,
                () -> scorecardService.sessionCards(" ", "用例生成", 10));
        assertThrows(ValidationException.class,
                () -> scorecardService.sessionCards("t278j5k.g", " ", 10));
        assertThrows(ValidationException.class,
                () -> scorecardService.sessionCards("t278j5k.g", "用例生成", 0));
        assertThrows(ValidationException.class,
                () -> scorecardService.sessionCards("t278j5k.g", "用例生成", 201));
        assertTrue(scorecardService.sessionCards("t278j5k.不存在族", "用例生成", 10)
                .isEmpty(), "未知标准族=空序列（无卡可画，禁抛错禁假点）");
    }

    
    private RubricView fixtureRubric(String tag) {
        return judgeRubricService.save(new RubricSaveInput("t278j5k." + tag, "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of()),
                        new RubricDimension("完整性", "覆盖全不全", 0.5, List.of())),
                List.of(), 70, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));
    }

    
    private RunView fixtureRunOn(RubricView rubric, String tag) {
        EvalSetVersionView set = judgeSampleService.publish("t278j5s." + tag, List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0),
                new SampleInput("需求B", "用例B", List.of(), "manual", 0),
                new SampleInput("需求C", "用例C", List.of(), "manual", 0)), 9101L);
        return judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j5-" + tag, null, 9101L));
    }

    private RunView fixtureRun(String tag) {
        return fixtureRunOn(fixtureRubric(tag), tag);
    }

    private Long sampleId(RunView run, String input) {
        return judgeSampleService.listSamples(run.evalsetName(), run.evalsetVersion())
                .stream().filter(s -> input.equals(s.input()))
                .map(SampleView::id).findFirst().orElseThrow();
    }

    private void save(RunView run, String sample, int rule, Map<String, Double> llm,
                      int tokens, long latency) {
        judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto.RecordInput(
                run.id(), sampleId(run, sample), rule, List.of("json-valid 通过"), llm,
                Map.of("相关性", "锚 offset 1-9"), latency, tokens));
    }

    private Long recordId(RunView run, String sample) {
        return jdbcTemplate.queryForObject("SELECT id FROM tp_judge_record WHERE run_id = ? "
                + "AND sample_id = ?", Long.class, run.id(), sampleId(run, sample));
    }

    @Test
    void 评分卡_权威分均值达线维度均分_改判回流优先() {
        RunView run = fixtureRun("card");
        save(run, "需求A", 80, Map.of("相关性", 90.0, "完整性", 60.0), 1000, 200);
        save(run, "需求B", 60, Map.of("相关性", 40.0, "完整性", 40.0), 500, 100);
        save(run, "需求C", 90, Map.of("相关性", 50.0, "完整性", 50.0), 200, 60);
        judgeReviewService.recordReview(new com.sixbean.testpike.core.judge.api.dto
                .JudgeReviewInput(recordId(run, "需求C"), 9102L, null, 85, "人审改判"));

        RunScorecardView card = scorecardService.scorecard(run.id());
        assertEquals(run.id(), card.runId());
        assertEquals(3, card.sampleTotal(), "样本总数=Run 绑定评测集版本行数");
        assertEquals(3, card.scored());
        assertEquals(70, card.avgFinalScore(),
                "均值必用人真值改判回流分（coalesce 口径，与 κ 统计面同源）");
        assertEquals(2, card.passCount(), "达线=改判后 85 计入（77/85≥70）");
        assertEquals(0.67, card.passRate(), 0.001, "达线率=2/3 四舍五入两位");
        assertEquals(1700L, card.tokensTotal());
        assertEquals(120.0, card.latencyAvgMs(), 0.001);
        assertEquals(60.0, card.dimensionAverages().get("相关性"), 0.001);
        assertEquals(50.0, card.dimensionAverages().get("完整性"), 0.001);
        assertEquals(70, card.passScore(), "门禁阈值取 Run 冻结快照");
    }

    @Test
    void 评分卡_零落分徽章不假装_均值空值口径() {
        RunView run = fixtureRun("empty");
        RunScorecardView card = scorecardService.scorecard(run.id());
        assertEquals(3, card.sampleTotal());
        assertEquals(0, card.scored());
        assertNull(card.avgFinalScore(), "无落分=null，禁 0 分假绿徽章");
        assertEquals(0, card.passCount());
        assertNull(card.passRate(), "除零口径 null（前端空态可解释）");
        assertTrue(card.dimensionAverages().isEmpty());
        assertEquals(0L, card.tokensTotal());
        assertNull(card.latencyAvgMs());
    }

    @Test
    void 评分卡_门禁阈值取快照_事后克隆标准不回拽历史卡() {
        RunView run = fixtureRun("drift");
        save(run, "需求A", 80, Map.of("相关性", 90.0, "完整性", 60.0), 100, 50);
        judgeRubricService.save(new RubricSaveInput("t278j5k.drift", "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of()),
                        new RubricDimension("完整性", "覆盖全不全", 0.5, List.of())),
                List.of(), 90, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));

        RunScorecardView card = scorecardService.scorecard(run.id());
        assertEquals(70, card.passScore(), "达线口径=Run 创建时冻结快照（归因红线）");
        assertEquals(1, card.passCount(), "77≥70 达线，不受新版 90 阈值回拽");
    }

    @Test
    void 评分卡_非法runId与不存在人话拒() {
        assertThrows(ValidationException.class, () -> scorecardService.scorecard(null));
        assertThrows(ValidationException.class, () -> scorecardService.scorecard(-1L));
        assertThrows(ValidationException.class, () -> scorecardService.scorecard(999999999L));
    }

    @Test
    void 趋势_同标准族已完成运行正序_未完成不入点_参数闸() {
        RubricView shared = fixtureRubric("fam");
        RunView r1 = fixtureRunOn(shared, "t1");
        RunView r2 = fixtureRunOn(shared, "t2");
        save(r1, "需求A", 60, Map.of("相关性", 40.0), 100, 50);
        save(r2, "需求A", 90, Map.of("相关性", 80.0), 200, 50);
        judgeRunService.start(r1.id());
        judgeRunService.finish(r1.id(), true);
        judgeRunService.start(r2.id());
        judgeRunService.finish(r2.id(), true);
        RunView r3 = fixtureRunOn(shared, "t3");
        judgeRunService.start(r3.id());

        List<ScorecardTrendPointView> points =
                scorecardService.trend(shared.name(), "用例生成", 10);
        assertEquals(2, points.size(), "仅已完成运行入趋势（跑批中/失败不算历史点）");
        assertEquals(r1.id(), points.get(0).runId(), "正序=历史演进左到右");
        assertEquals(r2.id(), points.get(1).runId());
        assertEquals(48, points.get(0).avgFinalScore());
        assertEquals(84, points.get(1).avgFinalScore());
        assertTrue(!points.get(0).finishedAt().isBlank(), "趋势点带终态时间可标轴");
        assertEquals(r1.evalsetName(), points.get(0).evalsetName());
        assertEquals(1, points.get(0).evalsetVersion(), "换题=新版本，曲线归因位");

        assertThrows(ValidationException.class, () -> scorecardService.trend(shared.name(), "用例生成", 0));
        assertThrows(ValidationException.class, () -> scorecardService.trend(shared.name(), "用例生成", 201));
        assertThrows(ValidationException.class, () -> scorecardService.trend(" ", "用例生成", 10));
        assertThrows(ValidationException.class, () -> scorecardService.trend(shared.name(), " ", 10));
        List<ScorecardTrendPointView> unknown =
                scorecardService.trend("t278j5k.不存在族", "用例生成", 10);
        assertTrue(unknown.isEmpty(), "未知标准族=空序列（无点可画，禁抛错禁假点）");
    }

    @Test
    void 趋势_limit截断取最近窗口() {
        RubricView shared = fixtureRubric("lim");
        EvalSetVersionView set = judgeSampleService.publish("t278j5s.lim", List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0)), 9101L);
        RunView last = null;
        for (int i = 1; i <= 3; i++) {
            RunView r = judgeRunService.createRun(new RunCreateInput(shared.id(),
                    set.setName(), set.version(), null, "pipeline", "t278j5-lim" + i,
                    null, 9101L));
            save(r, "需求A", 50 + i * 10, Map.of("相关性", 50.0), 100, 10);
            judgeRunService.start(r.id());
            judgeRunService.finish(r.id(), true);
            last = r;
        }
        List<ScorecardTrendPointView> window =
                scorecardService.trend(shared.name(), "用例生成", 1);
        assertEquals(1, window.size(), "limit=1 取最近一个已完成点");
        assertEquals(last.id(), window.get(0).runId());
    }
}
