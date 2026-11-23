package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.JudgeRunPageView;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evaluation-run history page (AW facade core side; direct read contract).
 * <p>Assertion faces: newest-first ordering, optional filters (set name,
 * status), total counts across pages, and fail-closed parameter gates
 * (page<1 / size>200 / status outside 0..3 rejected in plain language —
 * the run list never turns into an unbounded scan). Zero judgement: rows are
 * projected from tp_judge_run as-is (red line: status/anchor decisions stay
 * in createRun/start/finish, this read adds none).
 * <p>Real dev-DB run (slot env), no mocks; fixture rows are cleaned by the
 * runhist-* target_ref and runhist-set./runhist-rubric. name prefixes in
 * both @BeforeEach and @AfterEach (JudgeReadReportTest precedent).
 */
@SpringBootTest(classes = CoreApplication.class)
class JudgeRunListTest {

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
                + "(SELECT id FROM tp_judge_run WHERE target_ref LIKE 'runhist-%')");
        jdbcTemplate.update("DELETE FROM tp_judge_run WHERE target_ref LIKE 'runhist-%'");
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE 'runhist-set.%'");
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE 'runhist-rubric.%'");
    }

    /** One rubric × one set version under the given tag; returns [rubricId, set]. */
    private Object[] fixtureSet(String tag) {
        RubricView rubric = judgeRubricService.save(new RubricSaveInput("runhist-rubric." + tag,
                "用例生成",
                List.of(new RubricDimension("相关性", "是否切题", 0.5, List.of())),
                List.of(), 60, new BigDecimal("0.400"), new BigDecimal("0.600"), 9701L));
        EvalSetVersionView set = judgeSampleService.publish("runhist-set." + tag, List.of(
                new SampleInput("需求甲", "用例甲", List.of(), "manual", 0),
                new SampleInput("需求乙", "用例乙", List.of(), "manual", 0)), 9701L);
        return new Object[]{rubric.id(), set};
    }

    private RunView createRun(Long rubricId, EvalSetVersionView set, String tagRef) {
        return judgeRunService.createRun(new RunCreateInput(rubricId, set.setName(),
                set.version(), null, "external", "runhist-" + tagRef, null, 9701L));
    }

    @Test
    void listReturnsNewestFirstWithTotalAndFilters() {
        Object[] famA = fixtureSet("a");
        Object[] famB = fixtureSet("b");
        EvalSetVersionView setA = (EvalSetVersionView) famA[1];
        EvalSetVersionView setB = (EvalSetVersionView) famB[1];
        RunView a1 = createRun((Long) famA[0], setA, "a1");
        RunView a2 = createRun((Long) famA[0], setA, "a2");
        RunView b1 = createRun((Long) famB[0], setB, "b1");
        judgeRunService.start(a1.id());
        judgeRunService.finish(a1.id(), true);

        // Newest-first is id order: b1 was created last, so it heads the page.
        // Finishing a1 must not reorder the list (id DESC, not updated_at).
        JudgeRunPageView all = judgeRunService.listRuns(null, null, 1, 50);
        assertTrue(all.total() >= 3);
        assertEquals(b1.id(), all.runs().get(0).id(), "newest run first");
        assertEquals(a2.id(), all.runs().get(1).id(), "second newest stays in place");
        assertEquals(a1.id(), all.runs().get(2).id(), "finished run keeps its slot");

        JudgeRunPageView onlyA = judgeRunService.listRuns(setA.setName(), null, 1, 50);
        assertEquals(2, onlyA.total());
        assertTrue(onlyA.runs().stream().allMatch(r -> r.evalsetName().equals(setA.setName())));

        JudgeRunPageView done = judgeRunService.listRuns(setA.setName(), 2, 1, 50);
        assertEquals(1, done.total());
        assertEquals(a1.id(), done.runs().get(0).id());

        JudgeRunPageView pending = judgeRunService.listRuns(setA.setName(), 0, 1, 50);
        assertEquals(1, pending.total());
        assertEquals(a2.id(), pending.runs().get(0).id());
    }

    @Test
    void paginationSlicesStablyAcrossPages() {
        Object[] fam = fixtureSet("p");
        EvalSetVersionView set = (EvalSetVersionView) fam[1];
        for (int i = 0; i < 3; i++) {
            createRun((Long) fam[0], set, "p" + i);
        }
        JudgeRunPageView p1 = judgeRunService.listRuns(set.setName(), null, 1, 2);
        JudgeRunPageView p2 = judgeRunService.listRuns(set.setName(), null, 2, 2);
        assertEquals(3, p1.total());
        assertEquals(2, p1.runs().size());
        assertEquals(1, p2.runs().size());
        assertNotEquals(p1.runs().get(0).id(), p2.runs().get(0).id());
    }

    @Test
    void parameterGatesRejectFailClosedInPlainLanguage() {
        assertThrows(ValidationException.class,
                () -> judgeRunService.listRuns(null, null, 0, 20));
        ValidationException oversize = assertThrows(ValidationException.class,
                () -> judgeRunService.listRuns(null, null, 1, 201));
        assertTrue(oversize.getMessage().contains("200"));
        ValidationException badStatus = assertThrows(ValidationException.class,
                () -> judgeRunService.listRuns(null, 9, 1, 20));
        assertTrue(badStatus.getMessage().contains("状态"));
        // size=200 upper bound itself is allowed (boundary not silently tightened)
        assertFalse(judgeRunService.listRuns("runhist-set.none", null, 1, 200).total() > 0);
    }
}
