package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
import com.sixbean.testpike.core.judge.api.dto.RecordInput;
import com.sixbean.testpike.core.judge.api.dto.RecordView;
import com.sixbean.testpike.core.judge.api.dto.RunCostCardView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffRowView;
import com.sixbean.testpike.core.judge.api.dto.RunDiffView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardPageView;
import com.sixbean.testpike.core.judge.api.dto.RunSampleCardView;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeReadReportTest {

    private static final String PRICE_KEY = "judge.cost.price-per-1k-tokens";

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
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_review WHERE record_id IN "
                + "(SELECT r.id FROM tp_judge_record r JOIN tp_judge_run ru ON ru.id = r.run_id "
                + "WHERE ru.target_ref LIKE 't278j7r%')");
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j7r%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j7r%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j7rs.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j7rk.%'");
    }

    
    private Object[] fixtureFamily(String tag) {
        RubricView rubric = judgeRubricService.save(new RubricSaveInput("t278j7rk." + tag,
                "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of()),
                        new RubricDimension("完整性", "覆盖全不全", 0.5, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9701L));
        EvalSetVersionView set = judgeSampleService.publish("t278j7rs." + tag, List.of(
                new SampleInput("需求甲", "用例甲", List.of(), "manual", 0),
                new SampleInput("需求乙", "用例乙", List.of(), "batch-export", 0),
                new SampleInput("需求丙", "用例丙", List.of(), "manual", 0),
                new SampleInput("需求丁", "用例丁", List.of(), "manual", 0)), 9701L);
        RunView a = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j7ra-" + tag, null, 9701L));
        RunView b = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "external", "t278j7rb-" + tag, null, 9701L));
        return new Object[]{rubric, set, a, b};
    }

    private void score(RunView run, List<SampleView> samples, int idx, int ruleScore,
                       double rel, double comp, int tokens, long latencyMs) {
        judgeRecordService.saveScore(new RecordInput(run.id(), samples.get(idx).id(),
                ruleScore, List.of("regex-forbidden: 通过"),
                Map.of("相关性", rel, "完整性", comp),
                Map.of("相关性", "切题：" + samples.get(idx).input()),
                latencyMs, tokens));
    }

    @Test
    void test_sampleCards_worst_first_authoritative_order_and_card_fields() {
        Object[] f = fixtureFamily("sc1");
        EvalSetVersionView set = (EvalSetVersionView) f[1];
        RunView a = (RunView) f[2];
        List<SampleView> samples = judgeSampleService.listSamples(set.setName(), set.version());
        score(a, samples, 0, 100, 90, 80, 500, 1200);
        score(a, samples, 1, 60, 50, 50, 300, 800);
        score(a, samples, 2, 80, 60, 70, 200, 600);

        RunSampleCardPageView page = scorecardService.sampleCards(a.id(), 1, 50);
        assertEquals(3L, page.total(), "总数=已落分记录数");
        assertEquals(3, page.cards().size());
        List<RunSampleCardView> cards = page.cards();
        assertEquals(54, cards.get(0).authoritativeScore());
        assertEquals(71, cards.get(1).authoritativeScore());
        assertEquals(91, cards.get(2).authoritativeScore());
        RunSampleCardView worst = cards.get(0);
        assertEquals("batch-export", worst.sampleSource(), "来源徽标随样本行");
        assertEquals(List.of("regex-forbidden: 通过"), worst.ruleHits());
        assertEquals(50.0, ((Number) worst.llmScores().get("相关性")).doubleValue(), 0.001);
        assertNotNull(worst.reasons().get("相关性"), "每维理由与证据引用入卡");
        assertFalse(worst.reviewed(), "未改判位");
        assertNotNull(worst.inputPreview(), "被评输入预览入卡（低分队点开即看）");

        judgeReviewService.recordReview(new JudgeReviewInput(
                worst.recordId(), 9702L, null, 30, "人审改判测试"));
        RunSampleCardPageView after = scorecardService.sampleCards(a.id(), 1, 50);
        RunSampleCardView top = after.cards().get(0);
        assertEquals(30, top.authoritativeScore(), "权威分=coalesce(人工改判,机器合成) 单源");
        assertEquals(54, top.machineFinalScore(), "机器分原样留位可对照");
        assertEquals(30, top.manualFinalScore());
        assertTrue(top.reviewed(), "改判位徽标");
    }

    @Test
    void test_sampleCards_paging_and_guard_fail_closed() {
        Object[] f = fixtureFamily("sc2");
        RunView a = (RunView) f[2];
        ValidationException e0 = assertThrows(ValidationException.class,
                () -> scorecardService.sampleCards(0L, 1, 50));
        assertTrue(e0.getMessage().contains("runId"), "非法 runId 人话点名，实际=" + e0.getMessage());
        ValidationException e1 = assertThrows(ValidationException.class,
                () -> scorecardService.sampleCards(-3L, 1, 50));
        assertTrue(e1.getMessage().contains("runId"));
        assertThrows(ValidationException.class, () -> scorecardService.sampleCards(a.id(), 0, 50),
                "页码<1 拒");
        assertThrows(ValidationException.class, () -> scorecardService.sampleCards(a.id(), 1, 0),
                "尺寸<1 拒");
        assertThrows(ValidationException.class, () -> scorecardService.sampleCards(a.id(), 1, 201),
                "尺寸>200 拒（禁无界扫）");
        RunSampleCardPageView empty = scorecardService.sampleCards(a.id(), 1, 50);
        assertEquals(0L, empty.total());
        assertTrue(empty.cards().isEmpty());
        List<RunSampleCardView> beyond = scorecardService.sampleCards(a.id(), 999, 50).cards();
        assertTrue(beyond.isEmpty());
    }

    @Test
    void test_diff_three_verdicts_counts_and_footnote() {
        Object[] f = fixtureFamily("df1");
        EvalSetVersionView set = (EvalSetVersionView) f[1];
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        List<SampleView> samples = judgeSampleService.listSamples(set.setName(), set.version());
        score(a, samples, 0, 100, 90, 80, 500, 1000);
        score(a, samples, 1, 60, 50, 50, 400, 1000);
        score(a, samples, 2, 80, 60, 70, 300, 1000);
        score(a, samples, 3, 90, 85, 95, 200, 1000);
        score(b, samples, 0, 100, 90, 80, 500, 900);
        score(b, samples, 1, 100, 90, 80, 400, 900);
        score(b, samples, 2, 40, 50, 40, 300, 900);

        RunDiffView d = scorecardService.diffRuns(a.id(), b.id(), 1, 50);
        assertEquals(set.setName(), d.evalsetName());
        assertEquals(set.version(), d.evalsetVersion());
        assertEquals(3, d.comparedCount(), "双边同侧配对数（丁单边不计入配对）");
        assertEquals(1, d.upCount());
        assertEquals(1, d.downCount());
        assertEquals(1, d.flatCount());
        assertEquals(1, d.onlyACount());
        assertEquals(0, d.onlyBCount());
        Map<Long, RunDiffRowView> bySample = d.rows().stream()
                .collect(java.util.stream.Collectors.toMap(RunDiffRowView::sampleId, r -> r));
        assertEquals("持平", bySample.get(samples.get(0).id()).verdict());
        assertEquals(0, bySample.get(samples.get(0).id()).delta());
        assertEquals("涨", bySample.get(samples.get(1).id()).verdict());
        assertEquals(37, bySample.get(samples.get(1).id()).delta(), "delta=B-A");
        assertEquals("跌", bySample.get(samples.get(2).id()).verdict());
        assertEquals(-28, bySample.get(samples.get(2).id()).delta());
        assertEquals("仅A", bySample.get(samples.get(3).id()).verdict());
        assertNull(bySample.get(samples.get(3).id()).scoreB(), "B 侧缺分 null 诚实非 0");

        assertEquals(76.5, d.avgA(), 0.001);
        assertEquals(75.0, d.avgB(), 0.001);
        assertEquals(-1.5, d.avgDiff(), 0.001);
        assertEquals(0.08214, d.welchT(), 1e-3, "Welch t 手算锚（A−B 方向）");
        assertTrue(d.pValue() > 0.9 && d.pValue() <= 1.0, "p≈0.934，实际=" + d.pValue());
        assertFalse(d.significant(), "未达 α=0.05");
        assertNotNull(d.significanceNote());
        assertTrue(d.significanceNote().contains("不显著"), "人话注脚，实际=" + d.significanceNote());
    }

    // ------------------------------------------------------------------
    // page 15: the judge diff table orders server-side through
    // the four-key whitelist (sample_id / score_a / score_b / delta). Score
    // columns are merged-window Java-side derived values, so the whole union
    // window is ordered in memory before skip/limit — never a SQL ORDER BY.
    // Fixture faces (sample ids ascend s1<s2<s3<s4): s1 A91/B91 (delta 0),
    // s2 A54/B91 (delta +37), s3 A71/B43 (delta -28), s4 A91/B-missing (null).
    // ------------------------------------------------------------------

    /** Diff-sort fixture: same family as fixtureFamily, seeded with tie + gap. */
    private Object[] diffSortFixture(String tag) {
        Object[] f = fixtureFamily(tag);
        EvalSetVersionView set = (EvalSetVersionView) f[1];
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        List<SampleView> samples = judgeSampleService.listSamples(set.setName(), set.version());
        score(a, samples, 0, 100, 90, 80, 500, 1000);
        score(a, samples, 1, 60, 50, 50, 400, 1000);
        score(a, samples, 2, 80, 60, 70, 300, 1000);
        score(a, samples, 3, 100, 90, 80, 200, 1000);
        score(b, samples, 0, 100, 90, 80, 500, 900);
        score(b, samples, 1, 100, 90, 80, 400, 900);
        score(b, samples, 2, 40, 50, 40, 300, 900);
        return new Object[]{f[0], set, a, b, samples};
    }

    private List<Long> orderedSampleIds(RunDiffView d) {
        return d.rows().stream().map(RunDiffRowView::sampleId).toList();
    }

    @Test
    void test_diffSort_default_keeps_sample_id_asc_on_both_signatures() {
        Object[] f = diffSortFixture("ds1");
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        @SuppressWarnings("unchecked")
        List<SampleView> samples = (List<SampleView>) f[4];
        List<Long> asc = samples.stream().map(SampleView::id).toList();
        // zero-drift guard: both the legacy four-arg entry and the explicit
        // null pair keep the historical sample-id ascending default order
        assertEquals(asc, orderedSampleIds(scorecardService.diffRuns(a.id(), b.id(), 1, 50)),
                "四参旧口缺省序=样本号升序");
        assertEquals(asc,
                orderedSampleIds(scorecardService.diffRuns(a.id(), b.id(), 1, 50, null, null)),
                "显式 null 排序对与旧口同序");
    }

    @Test
    void test_diffSort_score_a_both_directions_with_tie_pinned() {
        Object[] f = diffSortFixture("ds2");
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        @SuppressWarnings("unchecked")
        List<SampleView> samples = (List<SampleView>) f[4];
        List<Long> s = samples.stream().map(SampleView::id).toList();
        // s1 and s4 tie at A=91 — the tied group is pinned by ascending sample
        // id in BOTH directions so paging stays deterministic
        assertEquals(List.of(s.get(0), s.get(3), s.get(2), s.get(1)),
                orderedSampleIds(scorecardService.diffRuns(a.id(), b.id(), 1, 50, "score_a", "desc")),
                "score_a desc：平局组按样本号升序钉");
        assertEquals(List.of(s.get(1), s.get(2), s.get(0), s.get(3)),
                orderedSampleIds(scorecardService.diffRuns(a.id(), b.id(), 1, 50, "score_a", "asc")),
                "score_a asc：平局组同样本号升序（双向同向钉）");
    }

    @Test
    void test_diffSort_delta_and_score_b_null_score_side_last() {
        Object[] f = diffSortFixture("ds3");
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        @SuppressWarnings("unchecked")
        List<SampleView> samples = (List<SampleView>) f[4];
        List<Long> s = samples.stream().map(SampleView::id).toList();
        // s4 has no B score so delta=null: the missing side sorts last in BOTH
        // directions (a missing score must never read as the largest gap)
        assertEquals(List.of(s.get(2), s.get(0), s.get(1), s.get(3)),
                orderedSampleIds(scorecardService.diffRuns(a.id(), b.id(), 1, 50, "delta", "asc")),
                "delta asc：缺分单边恒排末位");
        assertEquals(List.of(s.get(1), s.get(0), s.get(2), s.get(3)),
                orderedSampleIds(scorecardService.diffRuns(a.id(), b.id(), 1, 50, "delta", "desc")),
                "delta desc：缺分仍末位（禁读成最大差）");
        // score_b asc: s2 B=91 ties s1 (tie pinned by ascending id); s4 missing last
        assertEquals(List.of(s.get(2), s.get(0), s.get(1), s.get(3)),
                orderedSampleIds(scorecardService.diffRuns(a.id(), b.id(), 1, 50, "score_b", "asc")),
                "score_b asc：平局钉+缺分末位");
    }

    @Test
    void test_diffSort_pages_continue_over_whole_window() {
        Object[] f = diffSortFixture("ds4");
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        @SuppressWarnings("unchecked")
        List<SampleView> samples = (List<SampleView>) f[4];
        List<Long> s = samples.stream().map(SampleView::id).toList();
        // ordering covers the whole merged window before paging: page 2
        // continues where page 1 stopped (browser pageful sort could not)
        RunDiffView p1 = scorecardService.diffRuns(a.id(), b.id(), 1, 2, "score_a", "desc");
        RunDiffView p2 = scorecardService.diffRuns(a.id(), b.id(), 2, 2, "score_a", "desc");
        assertEquals(List.of(s.get(0), s.get(3)), orderedSampleIds(p1), "第一页=排序窗口头两件");
        assertEquals(List.of(s.get(2), s.get(1)), orderedSampleIds(p2), "第二页=窗口续序");
        assertEquals(4, p1.totalRows(), "total=并集窗口全量口径");
        assertEquals(4, p2.totalRows());
    }

    @Test
    void test_diffSort_offlist_and_injection_key_rejected_fail_closed() {
        Object[] f = diffSortFixture("ds5");
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        // injection-shaped key never reaches the DB read (gate short-circuits)
        ServiceException inj = assertThrows(ServiceException.class,
                () -> scorecardService.diffRuns(a.id(), b.id(), 1, 50,
                        "input; DROP TABLE tp_judge_run", "asc"),
                "注入形态键必须先于读库被闸拒");
        assertTrue(inj.getMessage().contains("白名单"),
                "人话拒点名白名单，实际=" + inj.getMessage());
        // off-list column of the same table stays out (verdict is derived)
        ServiceException off = assertThrows(ServiceException.class,
                () -> scorecardService.diffRuns(a.id(), b.id(), 1, 50, "verdict", "asc"),
                "越列键 verdict 拒收");
        assertTrue(off.getMessage().contains("白名单"));
        // rejection leaves the window intact: default order still readable
        assertEquals(4, scorecardService.diffRuns(a.id(), b.id(), 1, 50).totalRows(),
                "拒后四参缺省序仍可读");
    }

    @Test
    void test_diff_guard_same_run_and_version_mismatch() {
        Object[] f = fixtureFamily("df2");
        RunView a = (RunView) f[2];
        RunView b = (RunView) f[3];
        assertThrows(ValidationException.class, () -> scorecardService.diffRuns(a.id(), a.id(), 1, 50),
                "同 Run 自diff 人话拒");
        assertThrows(ValidationException.class, () -> scorecardService.diffRuns(a.id(), 999999999L, 1, 50),
                "运行不存在 fail-closed");
        EvalSetVersionView v2 = judgeSampleService.publish(((EvalSetVersionView) f[1]).setName(),
                List.of(new SampleInput("需求戊", "用例戊", List.of(), "manual", 0)), 9701L);
        RunView c = judgeRunService.createRun(new RunCreateInput(((RubricView) f[0]).id(),
                v2.setName(), v2.version(), null, "pipeline", "t278j7rc-df2", null, 9701L));
        ValidationException e = assertThrows(ValidationException.class,
                () -> scorecardService.diffRuns(a.id(), c.id(), 1, 50));
        assertTrue(e.getMessage().contains("评测集"), "人话点名不可比前提，实际=" + e.getMessage());
        assertTrue(e.getMessage().contains("v2") || e.getMessage().contains("版本"),
                "两侧版本号入文案");
    }

    @Test
    void test_cost_card_price_hot_update_dirty_fallback_and_zero_honesty() {
        Object[] f = fixtureFamily("ct1");
        EvalSetVersionView set = (EvalSetVersionView) f[1];
        RunView a = (RunView) f[2];
        List<SampleView> samples = judgeSampleService.listSamples(set.setName(), set.version());
        score(a, samples, 0, 90, 80, 80, 1500, 900);
        score(a, samples, 1, 90, 80, 80, 500, 700);

        ConfigInfo key = configService.list("AI评测").stream()
                .filter(c -> PRICE_KEY.equals(c.configKey())).findFirst()
                .orElseThrow(() -> new AssertionError("V212 牌价键未落库——先执行迁移再跑本测"));
        String original = configService.getValue(PRICE_KEY, "");
        try {
            configService.update(9701L, key.id(), new ConfigInput(PRICE_KEY, "",
                    key.groupName(), key.valueType(), 0, key.description()));
            RunCostCardView unpriced = scorecardService.costCard(a.id());
            assertEquals(2000L, unpriced.tokensTotal());
            assertFalse(unpriced.priceConfigured(), "空值=未设牌价");
            assertNull(unpriced.estimatedCost(), "未设牌价金额留空禁编造");
            assertTrue(unpriced.note().contains("牌价"), "注法人话点名，实际=" + unpriced.note());
            assertEquals(2, unpriced.scored());
            assertEquals(4, unpriced.sampleTotal());
            assertEquals(0.5, unpriced.samplingRate(), 0.0001, "抽样率=已落分/集版本样本数");
            assertEquals(1000L, unpriced.tokensPerSample(), "每样本 token 均价向下取整");

            configService.update(9701L, key.id(), new ConfigInput(PRICE_KEY, "2.5",
                    key.groupName(), key.valueType(), 0, key.description()));
            RunCostCardView priced = scorecardService.costCard(a.id());
            assertTrue(priced.priceConfigured());
            assertEquals(2.5, priced.pricePer1kTokens(), 0.0001);
            assertEquals(5.0, priced.estimatedCost(), 0.0001);
            assertEquals(2.5, priced.costPerSample(), 0.0001);

            configService.update(9701L, key.id(), new ConfigInput(PRICE_KEY, "abc",
                    key.groupName(), key.valueType(), 0, key.description()));
            assertFalse(scorecardService.costCard(a.id()).priceConfigured(), "脏值回退未设");
            configService.update(9701L, key.id(), new ConfigInput(PRICE_KEY, "-1",
                    key.groupName(), key.valueType(), 0, key.description()));
            assertFalse(scorecardService.costCard(a.id()).priceConfigured(), "负价回退未设");

            RunView emptyRun = (RunView) judgeRunService.createRun(new RunCreateInput(
                    ((RubricView) f[0]).id(), "t278j7rs.ct1", set.version(), null,
                    "pipeline", "t278j7re-ct1", null, 9701L));
            RunCostCardView zero = scorecardService.costCard(emptyRun.id());
            assertEquals(0L, zero.tokensTotal(), "无记录 token 合计 0（SQL coalesce 口径）");
            assertEquals(0.0, zero.samplingRate(), 0.0001, "0 落分/4 样本=0% 真值诚实");
            assertNull(zero.costPerSample(), "除零诚实留空");
        } finally {
            configService.update(9701L, key.id(), new ConfigInput(PRICE_KEY,
                    original == null ? "" : original,
                    key.groupName(), key.valueType(), 0, key.description()));
        }
    }
}
