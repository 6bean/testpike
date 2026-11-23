package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseQuery;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseKeywordSearchTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> trackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        // Shared dev database (R37): purge only the rows reachable from this
        // run's tracked fixture projects (cases, steps, tag links, audit) —
        // never a whole-table wipe that rewinds shared sequences and deletes
        // concurrent suites' and L3 probes' rows.
        ProjectFixtureRig.sweep(jdbcTemplate, trackedProjects);
        trackedProjects.clear();
    }

    private long createProject(String code) {
        // Same-code self-heal drops a crashed run's leftover before creating,
        // so the unique key never collides without touching other suites' rows.
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
    }

    private long createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                2, 2, "web", 1, 1, 1, null,
                List.of(new CaseStepInput(1, "步骤1", 0)))).id();
    }

    private List<CaseInfo> search(long projectId, String keyword) {
        return caseService.list(new CaseQuery(projectId, null, keyword, null, null, null, null, null,
                null, null, 1, 50)).list();
    }


    @Test
    void keywordMatchesCaseNo() {
        long p = createProject("N8A");
        createCase(p, "纯标题用例一号");
        createCase(p, "纯标题用例二号");
        List<CaseInfo> hit = search(p, "N8A-002");
        assertEquals(1, hit.size(), "keyword 应命中 case_no=TC-N8A-002 的用例");
        assertTrue(hit.get(0).title().contains("二号"), "命中的应是二号用例");
    }

    @Test
    void keywordMatchesTitle() {
        long p = createProject("N8B");
        createCase(p, "登录模块异常分支");
        createCase(p, "支付流程");
        List<CaseInfo> hit = search(p, "异常分支");
        assertEquals(1, hit.size());
        assertTrue(hit.get(0).title().contains("登录模块"));
    }


    @ParameterizedTest
    @ValueSource(strings = {"100%", "_", "'", "\\"})
    void keywordSpecialCharsNoError(String special) {
        long p = createProject("N8C");
        createCase(p, "覆盖率100%达标");
        createCase(p, "下划线_关键用例");
        createCase(p, "含'单引号");
        createCase(p, "含\\反斜杠");
        createCase(p, "普通标题");
        List<CaseInfo> hit = search(p, special);
        assertTrue(hit.size() <= 2, "特殊字符应按字面匹配而非通配，命中数应极小：got " + hit.size());
    }

    @Test
    void keywordPercentLiteralNotWildcard() {
        long p = createProject("N8D");
        createCase(p, "覆盖率100%达标");
        createCase(p, "覆盖率90%达标");
        createCase(p, "覆盖率80%达标");
        List<CaseInfo> hit = search(p, "100%");
        assertEquals(1, hit.size(), "100% 应字面匹配仅第一条，不得通配命中 90%/80%");
    }


    @Test
    void keywordBlankReturnsAll() {
        long p = createProject("N8E");
        createCase(p, "用例甲");
        createCase(p, "用例乙");
        createCase(p, "用例丙");
        assertEquals(3, search(p, "").size(), "空 keyword 应返回全部");
        assertEquals(3, search(p, null).size(), "null keyword 应返回全部");
        assertEquals(3, search(p, "   ").size(), "空白 keyword 应返回全部");
    }

    @Test
    void keywordTooLongTruncated() {
        long p = createProject("N8F");
        String head50 = "超长关键字前缀ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGH";
        createCase(p, head50 + "后缀");
        createCase(p, "无关用例");
        String tooLong = head50 + "后缀" + "x".repeat(100);
        List<CaseInfo> hit = search(p, tooLong);
        assertEquals(1, hit.size(), "超长 keyword 截断后应命中前缀相同的用例");
    }


    @Test
    void keywordCrossProjectIsolation() {
        long pa = createProject("N8GA");
        long pb = createProject("N8GB");
        createCase(pa, "Alpha专项登录用例");
        createCase(pb, "Alpha专项支付用例");
        List<CaseInfo> inA = search(pa, "Alpha专项");
        List<CaseInfo> inB = search(pb, "Alpha专项");
        assertEquals(1, inA.size(), "A 项目只应命中 A 的用例");
        assertEquals(1, inB.size(), "B 项目只应命中 B 的用例");
        assertTrue(inA.get(0).title().contains("登录"), "A 命中登录用例");
        assertTrue(inB.get(0).title().contains("支付"), "B 命中支付用例");
    }
}
