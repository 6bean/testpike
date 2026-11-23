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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-pack harvest tests : one-click
 * collection of hand-picked adopted excellent AI cases into a new immutable
 * golden eval-set version — the pack the drift replay pins template changes
 * against. Eligibility triple (AI source + excellent mark + review approved)
 * and the requirement-anchor rule refuse the WHOLE request with human-worded
 * named rows (fail-closed, no half version), and the snapshot digest stays
 * content-deterministic through the shared publish path.
 * <p>Real dev-DB round trip (strict testing: no mocks on service surfaces);
 * fixtures live in a dedicated project named with the marker below and are
 * cleaned by ids in Before/AfterEach — factory demo projects untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class JudgeGoldenHarvestTest {

    private static final String PROJECT_MARKER = "golden-harvest-fixture-project";
    private static final String CASE_NO_PREFIX = "GHX-";
    private static final String SET_PREFIX = "t544.gh.";

    @Autowired
    private JudgeSampleService judgeSampleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private final List<Long> createdCaseIds = new java.util.ArrayList<>();
    private final List<Long> createdRequirementIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUpFixtureProject() {
        cleanFixtures();
        String code = "GHX-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code) VALUES (?, ?)",
                PROJECT_MARKER, code);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE name = ? ORDER BY id DESC LIMIT 1",
                Long.class, PROJECT_MARKER);
    }

    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM tp_judge_sample WHERE set_name LIKE ?", SET_PREFIX + "%");
        if (!createdCaseIds.isEmpty()) {
            String in = String.join(",", createdCaseIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM tp_case_requirement WHERE case_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id IN (" + in + ")");
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE id IN (" + in + ")");
        }
        if (!createdRequirementIds.isEmpty()) {
            String in = String.join(",", createdRequirementIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM tp_requirement WHERE id IN (" + in + ")");
        }
        if (projectId != 0L) {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        createdCaseIds.clear();
        createdRequirementIds.clear();
        projectId = 0L;
    }

    /** Inserts one case per eligibility-gate shape (source/excellent/status). */
    private long insertCase(int source, int excellent, int status) {
        String caseNo = CASE_NO_PREFIX + System.nanoTime() + "-" + createdCaseIds.size();
        jdbcTemplate.update("INSERT INTO tp_test_case (case_no, project_id, title, status, source,"
                        + " excellent_flag, deleted) VALUES (?, ?, ?, ?, ?, ?, 0)",
                caseNo, projectId, "黄金收采夹具用例" + caseNo, status, source, excellent);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_test_case WHERE case_no = ?", Long.class, caseNo);
        createdCaseIds.add(id);
        return id;
    }

    private long insertRequirement(String title, String acceptance) {
        jdbcTemplate.update("INSERT INTO tp_requirement (project_id, source, title,"
                        + " acceptance_criteria, status) VALUES (?, 3, ?, ?, 1)",
                projectId, title, acceptance);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_requirement WHERE project_id = ? AND title = ?",
                Long.class, projectId, title);
        createdRequirementIds.add(id);
        return id;
    }

    private void link(long caseId, long requirementId) {
        jdbcTemplate.update("INSERT INTO tp_case_requirement (case_id, requirement_id)"
                + " VALUES (?, ?)", caseId, requirementId);
    }

    @Test
    void harvestAdoptedExcellentCasesCreatesGoldenVersion() {
        long caseA = insertCase(1, 1, 3);
        long caseB = insertCase(1, 1, 3);
        long reqA = insertRequirement("登录后列表分页", "翻页保留筛选条件");
        long reqB = insertRequirement("标签批量绑定", "一次绑定多用例且不重复");
        link(caseA, reqA);
        link(caseB, reqB);

        EvalSetVersionView pub = judgeSampleService.raiseFromAdoptedCases(
                SET_PREFIX + "ok", List.of(caseA, caseB), 9201L);

        assertEquals(SET_PREFIX + "ok", pub.setName());
        assertEquals(1, pub.version());
        assertEquals(2, pub.sampleCount());
        List<SampleView> rows = judgeSampleService.listSamples(SET_PREFIX + "ok", 1);
        assertEquals(2, rows.size());
        SampleView first = rows.get(0);
        assertEquals("batch-export", first.source());
        // Golden answer slot must not be empty — the harvest pins exactly the
        // human-released high-quality output.
        assertNotNull(first.expected());
        assertTrue(first.input().contains("【生成需求上下文】"), "input=需求上下文面");
        assertTrue(first.input().contains("登录后列表分页")
                        || first.input().contains("标签批量绑定"),
                "input 必含关联需求标题");
        assertTrue(first.expected().contains("【标题】黄金收采夹具用例"), "expected=已通过用例快照");
        assertTrue(first.referenceDocs().get(0).contains("\"sourceCaseId\""),
                "参考件=收采溯源锚 JSON");
        assertNotNull(first.snapshotDigest(), "快照锚经共享 publish 单源落位");
    }

    @Test
    void emptyHarvestIsRefused() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromAdoptedCases(SET_PREFIX + "e", List.of(), 9201L));
        assertTrue(ex.getMessage().contains("收采"), "空收采人话点名");
    }

    @Test
    void missingCaseIdsAreNamedAndWholeRequestRefused() {
        long caseA = insertCase(1, 1, 3);
        long reqA = insertRequirement("缺失核验", "整请求拒不落半截");
        link(caseA, reqA);
        long ghost = 999_999_999L;

        ValidationException ex = assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromAdoptedCases(
                        SET_PREFIX + "missing", List.of(caseA, ghost), 9201L));
        assertTrue(ex.getMessage().contains(String.valueOf(ghost)), "缺失 id 逐条点名");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM tp_judge_sample"
                + " WHERE set_name = ?", Integer.class, SET_PREFIX + "missing"), "零落库");
    }

    @Test
    void casesFailingEligibilityAreNamedAndRefused() {
        long manual = insertCase(3, 1, 3);
        long notExcellent = insertCase(1, 0, 3);
        long notApproved = insertCase(1, 1, 1);
        long req = insertRequirement("达标核验", "三闸逐条点名");
        link(manual, req);
        link(notExcellent, req);
        link(notApproved, req);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromAdoptedCases(SET_PREFIX + "gate",
                        List.of(manual, notExcellent, notApproved), 9201L));
        assertTrue(ex.getMessage().contains("非 AI 生成"), "点名缺 AI 生成源");
        assertTrue(ex.getMessage().contains("无优秀标记"), "点名缺优秀标");
        assertTrue(ex.getMessage().contains("评审未通过（当前待评审）"), "点名评审态人话");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM tp_judge_sample"
                + " WHERE set_name = ?", Integer.class, SET_PREFIX + "gate"), "整请求零落库");
    }

    @Test
    void caseWithoutRequirementAnchorIsRefused() {
        long caseA = insertCase(1, 1, 3);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> judgeSampleService.raiseFromAdoptedCases(
                        SET_PREFIX + "anchor", List.of(caseA), 9201L));
        assertTrue(ex.getMessage().contains("未关联任何需求"), "无锚不造题点名");
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM tp_judge_sample"
                + " WHERE set_name = ?", Integer.class, SET_PREFIX + "anchor"), "零落库");
    }

    @Test
    void sameContentDifferentOrderKeepsDigestAndPublishesNextVersion() {
        long caseA = insertCase(1, 1, 3);
        long caseB = insertCase(1, 1, 3);
        long reqA = insertRequirement("乱序锚定一", "同内容同快照");
        long reqB = insertRequirement("乱序锚定二", "版本递增不可变");
        link(caseA, reqA);
        link(caseB, reqB);

        EvalSetVersionView firstPub = judgeSampleService.raiseFromAdoptedCases(
                SET_PREFIX + "order", List.of(caseA, caseB), 9201L);
        EvalSetVersionView secondPub = judgeSampleService.raiseFromAdoptedCases(
                SET_PREFIX + "order", List.of(caseB, caseA), 9202L);

        assertEquals(2, secondPub.version(), "同名再收采=版本递增");
        assertEquals(firstPub.snapshotDigest(), secondPub.snapshotDigest(),
                "同内容乱序=同锚（判定确定性，快照单源）");
        assertEquals(4, jdbcTemplate.queryForObject("SELECT count(*) FROM tp_judge_sample"
                + " WHERE set_name = ?", Integer.class, SET_PREFIX + "order"), "历史版本行保留");
        assertNotEquals(secondPub.snapshotDigest(), "0".repeat(64));
    }
}
