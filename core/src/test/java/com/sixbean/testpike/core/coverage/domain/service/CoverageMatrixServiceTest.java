package com.sixbean.testpike.core.coverage.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.coverage.api.dto.CoverageMatrixView;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CoverageMatrixServiceTest {

    @Autowired
    private CoverageMatrixService coverageMatrixService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown (R37): only this suite's own project
        // subtrees — blanket TRUNCATE wiped concurrent chains' rows (r13).
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, fixtureProjectIds);
        fixtureProjectIds.clear();
    }

    private final List<Long> fixtureProjectIds = new ArrayList<>();

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    private long createCase(long projectId, String title, int priority, String endType) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                priority, 2, endType, 1, null, 2, null, List.of()));
        return info.id();
    }

    private long createRequirement(long projectId, String title, String chapter) {
        return requirementService.create(1L, new RequirementCreateInput(
                projectId, 3, null, title, "验收标准", chapter)).id();
    }


    @Test
    void 矩阵_关联覆盖与未覆盖高亮数据() {
        long pid = createProject("M1");
        long reqA = createRequirement(pid, "需求A", "章节一");
        long reqB = createRequirement(pid, "需求B", "章节一");
        long reqC = createRequirement(pid, "需求C", "章节二");
        long c1 = createCase(pid, "覆盖用例一", 1, "web");
        long c2 = createCase(pid, "覆盖用例二", 0, "mobile");
        createCase(pid, "无关联用例三", 2, "web");
        requirementService.linkCases(1L, reqA, List.of(c1, c2));
        requirementService.linkCases(1L, reqB, List.of(c2));

        CoverageMatrixView v = coverageMatrixService.matrix(pid, null, null, null);

        assertEquals(3, v.totalRequirements(), "需求点总数=3");
        assertEquals(2, v.coveredRequirements(), "已覆盖需求点=2（A/B）");
        assertEquals(1, v.uncoveredRequirementIds().size(), "未覆盖需求点=1（C）");
        assertTrue(v.uncoveredRequirementIds().contains(reqC), "未覆盖行黄色高亮数据（C）");
        assertEquals(2, v.chapters().size(), "按章节分组=2 组");
        var ch1 = v.chapters().stream().filter(c -> "章节一".equals(c.chapter())).findFirst().orElseThrow();
        assertEquals(2, ch1.requirements().size(), "章节一含需求 A/B");
        var reqARow = ch1.requirements().stream().filter(r -> r.id() == reqA).findFirst().orElseThrow();
        assertTrue(reqARow.covered(), "需求A 已覆盖");
        assertTrue(reqARow.caseIds().contains(c1) && reqARow.caseIds().contains(c2), "A 覆盖单元格 c1/c2");
        assertEquals(3, v.cases().size(), "用例列=3（含无关联用例）");
        var ch2 = v.chapters().stream().filter(c -> "章节二".equals(c.chapter())).findFirst().orElseThrow();
        var reqCRow = ch2.requirements().get(0);
        assertFalse(reqCRow.covered(), "需求C 未覆盖");
        assertTrue(reqCRow.caseIds().isEmpty(), "未覆盖单元格空白");
    }


    @Test
    void 矩阵_归档用例不计入覆盖() {
        long pid = createProject("M2");
        long reqA = createRequirement(pid, "需求A", "章");
        long c1 = createCase(pid, "覆盖用例", 1, "web");
        requirementService.linkCases(1L, reqA, List.of(c1));
        jdbcTemplate.update("UPDATE tp_test_case SET status = 9 WHERE id = ?", c1);

        CoverageMatrixView v = coverageMatrixService.matrix(pid, null, null, null);

        assertEquals(0, v.coveredRequirements(), "归档用例不计入覆盖（IT-J4-02）");
        assertTrue(v.uncoveredRequirementIds().contains(reqA), "归档后需求A 变未覆盖");
        assertTrue(v.cases().stream().noneMatch(c -> c.id() == c1), "归档用例不出现在列");
    }


    @Test
    void 矩阵_按优先级与终端筛选列() {
        long pid = createProject("M3");
        long reqA = createRequirement(pid, "需求A", "章");
        long cWeb = createCase(pid, "web用例", 0, "web");
        long cMobile = createCase(pid, "mobile用例", 1, "mobile");
        requirementService.linkCases(1L, reqA, List.of(cWeb, cMobile));

        CoverageMatrixView v0 = coverageMatrixService.matrix(pid, null, 0, null);
        assertEquals(1, v0.cases().size(), "优先级 0 筛选后 1 列");
        assertEquals(cWeb, v0.cases().get(0).id());
        assertEquals(1, v0.coveredRequirements(), "web 已关联仍覆盖");

        CoverageMatrixView vM = coverageMatrixService.matrix(pid, null, null, "mobile");
        assertEquals(1, vM.cases().size(), "mobile 筛选后 1 列");
        assertEquals(cMobile, vM.cases().get(0).id());
    }

    @Test
    void 矩阵_按章节筛选行() {
        long pid = createProject("M4");
        createRequirement(pid, "需求A", "章节一");
        createRequirement(pid, "需求B", "章节二");

        CoverageMatrixView v = coverageMatrixService.matrix(pid, "章节二", null, null);
        assertEquals(1, v.totalRequirements(), "章节筛选后 1 个需求");
        assertEquals("章节二", v.chapters().get(0).chapter());
    }


    @Test
    void 矩阵_停用行消失分母收缩_启用回算一致关联保留() {
        long pid = createProject("M5");
        long reqA = createRequirement(pid, "需求A", "章");
        long reqB = createRequirement(pid, "需求B", "章");
        long c1 = createCase(pid, "覆盖用例", 1, "web");
        requirementService.linkCases(1L, reqA, List.of(c1));

        CoverageMatrixView before = coverageMatrixService.matrix(pid, null, null, null);
        String beforeSnapshot = before.toString();

        requirementService.update(1L, reqA, new com.sixbean.testpike.core.requirement.api.dto
                .RequirementUpdateInput(null, null, null, null, null, 3, null));
        CoverageMatrixView off = coverageMatrixService.matrix(pid, null, null, null);
        assertEquals(1, off.totalRequirements(), "停用后分母收缩（行消失）");
        assertEquals(0, off.coveredRequirements(), "停用后分子=0（A 退出统计）");
        assertFalse(off.uncoveredRequirementIds().contains(reqA), "停用需求不在未覆盖名单（防红格误判）");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_requirement WHERE requirement_id IN"
                        + " (SELECT id FROM tp_requirement WHERE project_id = ?)",
                Integer.class, pid).intValue(),
                "停用不动关联行（关联保留可查，PRD 4.1.2 ③）");

        requirementService.update(1L, reqA, new com.sixbean.testpike.core.requirement.api.dto
                .RequirementUpdateInput(null, null, null, null, null, 1, null));
        CoverageMatrixView after = coverageMatrixService.matrix(pid, null, null, null);
        assertEquals(beforeSnapshot, after.toString(), "启用回算=停用前快照一致（四要素回归钉）");
    }


    @Test
    void 矩阵_跨项目数据隔离() {
        long pidA = createProject("MA");
        long pidB = createProject("MB");
        long reqA = createRequirement(pidA, "A需求", "章A");
        long reqB = createRequirement(pidB, "B需求", "章B");
        long cA = createCase(pidA, "A用例", 1, "web");
        long cB = createCase(pidB, "B用例", 1, "web");
        requirementService.linkCases(1L, reqA, List.of(cA));
        requirementService.linkCases(1L, reqB, List.of(cB));

        CoverageMatrixView vA = coverageMatrixService.matrix(pidA, null, null, null);

        assertEquals(1, vA.totalRequirements(), "A 矩阵不含 B 需求");
        assertEquals(1, vA.cases().size(), "A 矩阵不含 B 用例");
        assertEquals(cA, vA.cases().get(0).id());
        assertTrue(vA.chapters().get(0).requirements().get(0).caseIds().contains(cA),
                "A 覆盖单元格不含 B 用例");
    }
}
