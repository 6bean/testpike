package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
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
class JudgeRecordScoreTest {

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
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j1q%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j1q%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j1qs.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j1qk.%'");
    }

    
    private RunView fixtureRun(String tag) {
        RubricView rubric = judgeRubricService.save(new RubricSaveInput("t278j1qk." + tag,
                "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of()),
                        new RubricDimension("完整性", "覆盖全不全", 0.5, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));
        EvalSetVersionView set = judgeSampleService.publish("t278j1qs." + tag, List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0),
                new SampleInput("需求B", "用例B", List.of(), "manual", 0)), 9101L);
        return judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j1q-" + tag, null, 9101L));
    }

    private Long sampleId(RunView run, String input) {
        return judgeSampleService.listSamples(run.evalsetName(), run.evalsetVersion())
                .stream().filter(s -> input.equals(s.input()))
                .map(SampleView::id).findFirst().orElseThrow();
    }

    @Test
    void 混合合成_维度加权均值乘权重_四舍五入落库() {
        RunView run = fixtureRun("mix");
        var saved = judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                .RecordInput(run.id(), sampleId(run, "需求A"), 100,
                List.of("json-valid 通过"), Map.of("相关性", 90.0, "完整性", 60.0),
                Map.of("相关性", "切题（引用锚 offset 12-30）"), 350L, 1200));

        assertEquals(85, saved.finalScore(), "合成=Σ规则×w_rule+ΣLLM维加权×w_llm（快照冻结权重）");
        assertEquals(100, saved.ruleScore());
        assertEquals(2, saved.llmScores().size());
        assertEquals("切题（引用锚 offset 12-30）", saved.reasons().get("相关性"));
        assertEquals(350L, saved.latencyMs());
        assertEquals(1200, saved.tokens());
        assertNull(saved.manualFinalScore(), "人工终判位初值空（J4 改判消费位）");
        assertNotNull(saved.id());
    }

    @Test
    void 零LLM特例_规则分直取() {
        RunView run = fixtureRun("ruleonly");
        var saved = judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                .RecordInput(run.id(), sampleId(run, "需求A"), 72,
                List.of("sensitive-word 命中"), null, null, null, null));
        assertEquals(72, saved.finalScore(),
                "无 LLM 分=评分卡退化口径（6.3：规则维全占，不乘权重摊薄）");
    }

    @Test
    void 幂等重复提交_单行更新() {
        RunView run = fixtureRun("idem");
        Long sid = sampleId(run, "需求B");
        judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                .RecordInput(run.id(), sid, 50, List.of(), Map.of("相关性", 50.0),
                Map.of("相关性", "初判"), 100L, 500));
        var again = judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                .RecordInput(run.id(), sid, 80, List.of(), Map.of("相关性", 80.0),
                Map.of("相关性", "重判改分"), 200L, 900));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_record WHERE run_id = ? AND sample_id = ?",
                Integer.class, run.id(), sid);
        assertEquals(1, count, "uk(run_id,sample_id) 单行（重评=更新非翻倍）");
        assertEquals(80, again.finalScore());
        assertEquals("重判改分", again.reasons().get("相关性"));
    }

    @Test
    void 样本不属于运行绑定版本_人话拒_防串集() {
        RunView run = fixtureRun("cross");
        Long other = judgeSampleService.publish("t278j1qs.other", List.of(
                new SampleInput("需求X", null, List.of(), "manual", 0)), 9101L) == null
                ? -1L : sampleIdOfOther("需求X");

        ValidationException e = assertThrows(ValidationException.class,
                () -> judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                        .RecordInput(run.id(), other, 50, List.of(), null, null, null, null)));
        assertTrue(e.getMessage().contains("评测集"), e.getMessage());
    }

    private Long sampleIdOfOther(String input) {
        List<SampleView> rows = judgeSampleService.listSamples("t278j1qs.other", 1);
        return rows.stream().filter(s -> input.equals(s.input()))
                .map(SampleView::id).findFirst().orElseThrow();
    }

    @Test
    void 越界分与双空分_人话点名() {
        RunView run = fixtureRun("bad");
        assertThrows(ValidationException.class,
                () -> judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                        .RecordInput(run.id(), sampleId(run, "需求A"), 150,
                        List.of(), null, null, null, null)), "规则分越界 0~100");

        assertThrows(ValidationException.class,
                () -> judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                        .RecordInput(run.id(), sampleId(run, "需求A"), null,
                        List.of(), null, null, null, null)), "双空分=无意义落分");

        assertThrows(ValidationException.class,
                () -> judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                        .RecordInput(run.id(), 999999999L, 50,
                        List.of(), null, null, null, null)), "未知样本=fail-closed");
    }

    @Test
    void 快照权重演进不回拽历史落分() {
        RunView run = fixtureRun("drift");
        var first = judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                .RecordInput(run.id(), sampleId(run, "需求A"), 100, List.of(),
                Map.of("相关性", 100.0, "完整性", 100.0), Map.of(), null, null));
        assertEquals(100, first.finalScore());

        RubricView current = judgeRubricService.detail(
                judgeRubricService.listVersions(run.rubricName(), run.rubricDomain()).get(0).id());
        judgeRubricService.save(new RubricSaveInput(current.name(), current.domain(),
                current.dimensions(), current.rules(), current.passScore(),
                new BigDecimal("0.900"), new BigDecimal("0.100"), 9102L));

        var again = judgeRecordService.saveScore(new com.sixbean.testpike.core.judge.api.dto
                .RecordInput(run.id(), sampleId(run, "需求A"), 0, List.of(),
                Map.of("相关性", 100.0, "完整性", 100.0), Map.of(), null, null));
        assertEquals(60, again.finalScore(), "合成权重取 Run 冻结快照（事后改标准不改历史归因）");
    }
}
