package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.judge.api.dto.CalibrationGroupView;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.JudgeReviewInput;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeCalibrationProfileTest {

    private static final String BOOTSTRAP_KEY = "judge.calibration.bootstrap-samples";

    @Autowired
    private JudgeCalibrationProfileService profileService;

    @Autowired
    private JudgeRubricService judgeRubricService;

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private JudgeRunService judgeRunService;

    @Autowired
    private JudgeRecordService judgeRecordService;

    @Autowired
    private JudgeReviewService judgeReviewService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_review WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j4c%')");
        jdbcTemplate.update("DELETE FROM tp_judge_record WHERE run_id IN "
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j4c%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j4c%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j4c.s%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j4c.k%'");
        jdbcTemplate.update("DELETE FROM tp_llm_config WHERE name LIKE 't278j4c.m%'");
    }

    @AfterEach
    void restoreCalibrationKey() {
        setBootstrap("200");
        cleanFixture();
    }

    private static final String BOOTSTRAP_SEED_DESCRIPTION =
            "校准基线所需改判配对数（未达=全量双裁判建基线，≥1 越界/脏值回退默认 200，热更即生效）";

    private void setBootstrap(String value) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM tp_sys_config WHERE config_key = ?", Long.class, BOOTSTRAP_KEY);
        if (ids.isEmpty()) {
            configService.create(9101L, new ConfigInput(BOOTSTRAP_KEY, value, "AI评测", "int", 0,
                    BOOTSTRAP_SEED_DESCRIPTION));
            return;
        }
        // Keep the stored description when flipping the value: a test must never write
        // internal wording back onto the user-visible config page.
        String description = jdbcTemplate.queryForObject(
                "SELECT description FROM tp_sys_config WHERE id = ?", String.class, ids.get(0));
        if (description == null || description.isBlank()) {
            description = BOOTSTRAP_SEED_DESCRIPTION;
        }
        configService.update(9101L, ids.get(0),
                new ConfigInput(BOOTSTRAP_KEY, value, "AI评测", "int", 0, description));
    }

    
    private void fixtureGroup(String tag, String rubricKey, Long llmConfigId, int[][] pairs) {
        RubricView rubric = judgeRubricService.save(new RubricSaveInput("t278j4c.k." + rubricKey,
                "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 1.0, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));
        List<SampleInput> samples = new ArrayList<>();
        for (int i = 0; i < pairs.length; i++) {
            samples.add(new SampleInput("需求A-" + tag + "-" + i, "用例A", List.of(), "manual", 0));
        }
        EvalSetVersionView set = judgeSampleService.publish("t278j4c.s." + tag, samples, 9101L);
        RunView run = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j4c-" + tag, llmConfigId, 9101L));
        List<Long> sids = judgeSampleService.listSamples(set.setName(), set.version()).stream()
                .map(SampleView::id).sorted().toList();
        assertEquals(pairs.length, sids.size(), "夹具样本数=配对数（uk(run,sample) 一配对一个样本）");
        for (int i = 0; i < pairs.length; i++) {
            int rule = pairs[i][0] >= 60 ? 100 : 0;
            RecordView rec = judgeRecordService.saveScore(new RecordInput(run.id(), sids.get(i),
                    rule, List.of("json-valid 通过"), Map.of("相关性", (double) pairs[i][0]),
                    Map.of("相关性", "有据（引用锚 offset 0-8）"), 100L, 50));
            judgeReviewService.recordReview(new JudgeReviewInput(rec.id(), 9102L, null,
                    pairs[i][1], "测试改判"));
        }
    }

    private Long newModel(String tag) {
        return jdbcTemplate.queryForObject("INSERT INTO tp_llm_config (name, provider, model_name) "
                + "VALUES ('t278j4c.m." + tag + "', 'openai', 'judge-x') RETURNING id", Long.class);
    }

    private CalibrationGroupView find(List<CalibrationGroupView> all, String rubricKey, Long modelId) {
        return all.stream()
                .filter(g -> g.rubricName().equals("t278j4c.k." + rubricKey))
                .filter(g -> java.util.Objects.equals(g.llmConfigId(), modelId))
                .findFirst().orElseThrow(() -> new AssertionError("组缺失：" + rubricKey + "/" + modelId));
    }

    @Test
    void 全一致组_基线达成_qualified且kappa_rho为1() {
        setBootstrap("1");
        Long m1 = newModel("a1");
        fixtureGroup("g1", "r1", m1, new int[][]{{100, 100}, {0, 0}, {100, 100}});

        List<CalibrationGroupView> profile = profileService.profile("t278j4c.k.r1", "用例生成", null);
        assertEquals(1, profile.size(), "过滤=rubric 族内只此一组");
        CalibrationGroupView g = profile.get(0);
        assertEquals(3, g.pairCount());
        assertEquals(1.0, g.kappaPass(), 1e-9, "po=1 pe=5/9 → κ=1（面值锚对账）");
        assertEquals(1.0, g.spearman(), 1e-9);
        assertTrue(g.qualified(), "配对≥基线 且 κ≥达标线=可入准出谓词");
        assertNotNull(g.note());
    }

    @Test
    void 单配对kappa无定义_判不了留痕_禁混跌破() {
        setBootstrap("1");
        Long m1 = newModel("b1");
        fixtureGroup("g2", "r2", m1, new int[][]{{100, 100}});

        CalibrationGroupView g = find(profileService.profile(null, null, null), "r2", m1);
        assertEquals(1, g.pairCount());
        assertTrue(Double.isNaN(g.kappaPass()), "n=1 双侧同档=pe=1 无定义=NaN（判不了）");
        assertFalse(g.qualified(), "判不了不得虚过门禁");
        assertTrue(g.note().contains("未定义"), "无定义单独留痕「判不了该补样」，禁与「跌破=不一致」混写：" + g.note());
    }

    @Test
    void null模型也成组_独立于同模型他rubric组() {
        setBootstrap("1");
        Long m1 = newModel("c1");
        fixtureGroup("g3", "r3", null, new int[][]{{100, 100}, {0, 0}});
        fixtureGroup("g3m", "r3", m1, new int[][]{{100, 0}});

        List<CalibrationGroupView> profile = profileService.profile("t278j4c.k.r3", "用例生成", null);
        assertEquals(2, profile.size(), "同 rubric 不同 judge 模型=两组（档案按组合切分）");
        CalibrationGroupView nullGroup = find(profile, "r3", null);
        assertNull(nullGroup.llmConfigId());
        assertEquals(2, nullGroup.pairCount());
        assertEquals(1.0, nullGroup.kappaPass(), 1e-9);
        assertTrue(nullGroup.qualified());
    }

    @Test
    void 完全反向组_kappa为负数_不一致语义非NaN() {
        setBootstrap("1");
        Long m2 = newModel("d1");
        fixtureGroup("g4", "r4", m2, new int[][]{{100, 0}, {0, 100}});

        CalibrationGroupView g = find(profileService.profile("t278j4c.k.r4", "用例生成", null), "r4", m2);
        assertEquals(-1.0, g.kappaPass(), 1e-9, "po=0 pe=0.5 → κ=-1（0=不一致该拦，NaN=判不了该补样，语义分开）");
        assertEquals(-1.0, g.spearman(), 1e-9);
        assertFalse(g.qualified());
        assertTrue(g.note().contains("跌破"), g.note());
    }

    @Test
    void 过滤位失效回归_不存在的rubric名必空集_L3抓出禁全量放行() {
        setBootstrap("1");
        Long m1 = newModel("f1");
        fixtureGroup("g6", "r6", m1, new int[][]{{100, 100}});

        assertTrue(profileService.profile("t278j4c.k.不存在族", "用例生成", null).isEmpty(),
                "过滤位空命中=空集，禁把别组配对混进本组档案");
        assertTrue(profileService.profile("t278j4c.k.r6", "别的域", null).isEmpty(),
                "域过滤位同样必生效");
        assertEquals(1, profileService.profile("t278j4c.k.r6", "用例生成", null).size(),
                "正命中标定：过滤生效≠恒空");
    }

    @Test
    void 维度级流水不进校准档案() {
        setBootstrap("1");
        Long m1 = newModel("e1");
        fixtureGroup("g5", "r5", m1, new int[][]{{100, 100}});
        Long recId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_judge_record WHERE run_id IN "
                        + "(SELECT id FROM tp_judge_run WHERE target_ref = 't278j4c-g5')", Long.class);
        judgeReviewService.recordReview(new JudgeReviewInput(recId, 9102L, "相关性", 30, "维度改判"));

        CalibrationGroupView g = find(profileService.profile("t278j4c.k.r5", "用例生成", null), "r5", m1);
        assertEquals(1, g.pairCount(), "κ/Spearman 配对只认总改判行（维度流水 org/new 不同口径禁入档案）");
    }
}
