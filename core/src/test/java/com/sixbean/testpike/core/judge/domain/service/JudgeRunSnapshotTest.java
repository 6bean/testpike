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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JudgeRunSnapshotTest {

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
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 't278j1r%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 't278j1r%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 't278j1rs.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 't278j1rk.%'");
    }

    private RubricView newRubric(String name) {
        return judgeRubricService.save(new RubricSaveInput(name, "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of("9 分长这样")),
                        new RubricDimension("完整性", "覆盖全不全", 0.5, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9101L));
    }

    private EvalSetVersionView newEvalSet(String setName) {
        return judgeSampleService.publish(setName, List.of(
                new SampleInput("需求A", "用例A", List.of(), "manual", 0)), 9101L);
    }

    @Test
    void 创建评测运行_双锚冻结_事后克隆标准不回拽() {
        RubricView rubric = newRubric("t278j1rk.basic");
        EvalSetVersionView set = newEvalSet("t278j1rs.basic");

        RunView run = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j1r-basic", null, 9101L));

        assertEquals(0, run.status(), "新建=待跑");
        assertEquals(rubric.name(), run.rubricName());
        assertEquals(rubric.version(), run.rubricVersion());
        assertEquals(set.snapshotDigest(), run.evalsetDigest(),
                "digest 回读自样本表（三元组绑定，§6.5 归因前提）");
        Map<String, Object> snap = run.rubricSnapshot();
        assertNotNull(snap.get("dimensions"), "快照冻结维度全量");
        assertEquals(60, ((Number) snap.get("passScore")).intValue());
        assertEquals(2, ((List<?>) snap.get("dimensions")).size());

        judgeRubricService.cloneVersion(rubric.id(), 9102L);
        RunView reread = judgeRunService.detail(run.id());
        assertEquals(rubric.version(), reread.rubricVersion());
        assertEquals(rubric.version(),
                ((Number) reread.rubricSnapshot().get("version")).intValue(),
                "快照内 version 仍钉创建时版本（分数归因不被标准演进污染）");

        RubricView v2 = judgeRubricService.detail(
                judgeRubricService.listVersions("t278j1rk.basic", "用例生成").get(0).id());
        RunView run2 = judgeRunService.createRun(new RunCreateInput(v2.id(),
                set.setName(), set.version(), null, "external", "t278j1r-ext", null, 9101L));
        assertEquals(2, run2.rubricVersion(), "新 Run 绑克隆后的新版本");
    }

    @Test
    void 回读校验_传错锚与未知版本_人话点名() {
        RubricView rubric = newRubric("t278j1rk.digest");
        EvalSetVersionView set = newEvalSet("t278j1rs.digest");

        ValidationException e1 = assertThrows(ValidationException.class,
                () -> judgeRunService.createRun(new RunCreateInput(rubric.id(),
                        set.setName(), set.version(), "f".repeat(64),
                        "pipeline", "t278j1r-d1", null, 9101L)));
        assertTrue(e1.getMessage().contains("锚"), e1.getMessage());

        assertThrows(ValidationException.class,
                () -> judgeRunService.createRun(new RunCreateInput(rubric.id(),
                        set.setName(), 99, null, "pipeline", "t278j1r-d2", null, 9101L)),
                "评测集版本不存在=fail-closed（禁空集假跑）");

        assertThrows(ValidationException.class,
                () -> judgeRunService.createRun(new RunCreateInput(999999999L,
                        set.setName(), set.version(), null, "pipeline", "t278j1r-d3", null, 9101L)),
                "未知评分标准=人话拒");

        ValidationException e4 = assertThrows(ValidationException.class,
                () -> judgeRunService.createRun(new RunCreateInput(rubric.id(),
                        set.setName(), set.version(), null, "telepathy", "t278j1r-d4", null, 9101L)));
        assertTrue(e4.getMessage().contains("pipeline"), e4.getMessage());

        assertThrows(ValidationException.class,
                () -> judgeRunService.createRun(new RunCreateInput(rubric.id(),
                        set.setName(), set.version(), null, "pipeline", " ", null, 9101L)));
    }

    @Test
    void 状态迁移_待跑_跑批中_完成或失败_非法迁移人话拒() {
        RubricView rubric = newRubric("t278j1rk.flow");
        EvalSetVersionView set = newEvalSet("t278j1rs.flow");
        RunView run = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j1r-flow", null, 9101L));

        assertThrows(ValidationException.class, () -> judgeRunService.finish(run.id(), true));

        judgeRunService.start(run.id());
        assertEquals(1, judgeRunService.detail(run.id()).status());
        assertThrows(ValidationException.class, () -> judgeRunService.start(run.id()));

        judgeRunService.finish(run.id(), true);
        RunView done = judgeRunService.detail(run.id());
        assertEquals(2, done.status());
        assertNotNull(done.finishedAt(), "完成即落终态时间");
        assertThrows(ValidationException.class, () -> judgeRunService.finish(run.id(), false));

        RunView failed = judgeRunService.createRun(new RunCreateInput(rubric.id(),
                set.setName(), set.version(), null, "pipeline", "t278j1r-fail", null, 9101L));
        judgeRunService.start(failed.id());
        judgeRunService.finish(failed.id(), false);
        assertEquals(3, judgeRunService.detail(failed.id()).status(), "失败终态=3");

        assertThrows(ValidationException.class, () -> judgeRunService.start(999999999L),
                "未知运行=fail-closed");
    }
}
