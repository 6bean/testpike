package com.sixbean.testpike.core.judge.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.judge.api.dto.EvalSetVersionView;
import com.sixbean.testpike.core.judge.api.dto.JudgeRuleItem;
import com.sixbean.testpike.core.judge.api.dto.RubricDimension;
import com.sixbean.testpike.core.judge.api.dto.RubricFamilyView;
import com.sixbean.testpike.core.judge.api.dto.RubricSaveInput;
import com.sixbean.testpike.core.judge.api.dto.RubricView;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only enumeration doors added by segment A (the run-creation picker
 * needs to list rubric families and evaluation-set versions over HTTP; until
 * now both existed only inside core service call sites, so the frontend made
 * the user type ids by hand).
 * <p>Assertion faces: ①rubric family heads = exactly one latest-version row
 * per (name, domain), no cross-family bleed, picker fields round-tripped;
 * ②set versions = one row per (set_name, version) segment with the publish
 * digest and the real sample count, newest first; ③both doors are read-only —
 * calling them never bumps a version and returns empty (not an error) when no
 * fixture exists. Real dev-DB runs (strict-testing: no mocks), fixture rows
 * live under the t598a. namespace and are cleaned before/after each test
 * (R37: only fixture-owned rows are deleted, shipped baseline untouched).
 */
@SpringBootTest(classes = CoreApplication.class)
class JudgeEvalOptionReadTest {

    private static final String FIXTURE_LIKE = "t598a.%";

    @Autowired
    private JudgeRubricService rubricService;

    @Autowired
    private JudgeSampleService sampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_judge_rubric WHERE name LIKE ?", FIXTURE_LIKE);
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE ?", FIXTURE_LIKE);
    }

    private RubricSaveInput rubricInput(String name, String domain) {
        return new RubricSaveInput(name, domain,
                List.of(new RubricDimension("相关性", "输出与需求的关联程度", 1.0,
                        List.of("9 分长这样：逐条对应需求点"))),
                List.of(new JudgeRuleItem("json-valid", Map.of("strict", true))),
                70, new BigDecimal("0.400"), new BigDecimal("0.600"), 9201L);
    }

    private List<SampleInput> samples(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new SampleInput("t598a 输入 " + i, "t598a 期望 " + i,
                        List.of(), "manual", 0))
                .toList();
    }

    @Test
    void rubricFamilyHeadsReturnOneLatestRowPerNameAndDomain() {
        rubricService.save(rubricInput("t598a.用例质量", "用例生成"));
        RubricView second = rubricService.save(rubricInput("t598a.用例质量", "用例生成"));
        rubricService.save(rubricInput("t598a.用例质量", "归因准确性"));

        List<RubricFamilyView> heads = rubricService.listLatest().stream()
                .filter(v -> v.name().startsWith("t598a.")).toList();

        assertEquals(2, heads.size(), "one row per (name, domain) family");
        RubricView latestCase = second;
        RubricFamilyView caseHead = heads.stream()
                .filter(v -> "用例生成".equals(v.domain())).findFirst().orElseThrow();
        assertEquals(latestCase.id(), caseHead.id(), "head row is the latest version");
        assertEquals(2, caseHead.version());
        assertEquals(70, caseHead.passScore());
        assertEquals(1, caseHead.status(), "fresh family stays gray");
        assertNotNull(caseHead.createdAt());
        RubricFamilyView attributionHead = heads.stream()
                .filter(v -> "归因准确性".equals(v.domain())).findFirst().orElseThrow();
        assertEquals(1, attributionHead.version(), "other family keeps its own head");
    }

    @Test
    void evalsetVersionsEnumerateEachSegmentWithDigestAndCount() {
        EvalSetVersionView v1 = sampleService.publish("t598a.回归集", samples(2), 9201L);
        EvalSetVersionView v2 = sampleService.publish("t598a.回归集", samples(3), 9201L);

        List<EvalSetVersionView> rows = sampleService.listSetVersions().stream()
                .filter(v -> v.setName().startsWith("t598a.")).toList();

        assertEquals(2, rows.size(), "one row per published version segment");
        assertEquals(2, rows.get(0).version(), "newest version first");
        assertEquals(3, rows.get(0).sampleCount());
        assertEquals(v2.snapshotDigest(), rows.get(0).snapshotDigest());
        assertEquals(1, rows.get(1).version());
        assertEquals(2, rows.get(1).sampleCount());
        assertEquals(v1.snapshotDigest(), rows.get(1).snapshotDigest());
    }

    @Test
    void enumerationIsReadOnlyAndEmptyWithoutFixtures() {
        long rubricRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_rubric WHERE name LIKE ?", Long.class, FIXTURE_LIKE);
        long sampleRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_sample WHERE set_name LIKE ?", Long.class, FIXTURE_LIKE);
        assertEquals(0, rubricRows);
        assertEquals(0, sampleRows);

        List<RubricFamilyView> heads = rubricService.listLatest().stream()
                .filter(v -> v.name().startsWith("t598a.")).toList();
        List<EvalSetVersionView> versions = sampleService.listSetVersions().stream()
                .filter(v -> v.setName().startsWith("t598a.")).toList();

        assertTrue(heads.isEmpty() && versions.isEmpty(), "no fixture = empty list, never an error");

        rubricService.listLatest();
        sampleService.listSetVersions();
        long rubricRowsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_rubric WHERE name LIKE ?", Long.class, FIXTURE_LIKE);
        long sampleRowsAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_judge_sample WHERE set_name LIKE ?", Long.class, FIXTURE_LIKE);
        assertEquals(rubricRows, rubricRowsAfter, "read door never writes");
        assertEquals(sampleRows, sampleRowsAfter, "read door never writes");
    }
}
