package com.sixbean.testpike.core.webelement.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.webelement.api.dto.ReferencingCasePage;
import com.sixbean.testpike.core.webelement.api.dto.ReferencingCaseView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read side of "cases that reference this element" (S1 stage element ledger
 * door). The consumer is the Page Element Library detail drawer: given one
 * ledger element, list the live case steps of the same project whose step
 * anchor evidence names that element.
 * <p>Why this gate exists: the anchor ledger (tp_case_step.anchor) is already
 * written by the AI landing, manual edit and script write-back chains, so the
 * reference list must consume that existing ledger directly — no second index
 * table, no recomputed copy (single-source rule for shared logic).
 * <p>Contracts pinned here:
 * ① the canonical single-ref dialect {kind:elem, ref:name} matches;
 * ② the multi-ref dialect {kind:elem, refs:[a,b]} matches on member names and
 * never on a non-member;
 * ③ anchors of other kinds (source/contract) carrying the same text are not
 * counted as element references;
 * ④ the list is a project segment — a same-named case in another project stays
 * invisible;
 * ⑤ soft-deleted cases and stale (non-current) version step rows are excluded;
 * ⑥ same-named elements across different pages both answer the name-union
 * list (the anchor stores names only, so the ledger shows it honestly by
 * name instead of filtering by page on its own);
 * ⑦ paging is bounded with a server-side clamp (max 100, illegal inputs
 * degrade to 20) and an exact total;
 * ⑧ an unknown element returns an empty page, never an error.
 * <p>Real chain against the live slot database, fixtures created per id and
 * purged by id in teardown (no factory-baseline writes).
 */
@SpringBootTest(classes = CoreApplication.class)
class WebElementReferencingCasesReadTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private ElementReferencingCaseQuery query;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> projectIds = new ArrayList<>();
    private final List<Long> elementIds = new ArrayList<>();
    private final List<Long> caseIds = new ArrayList<>();

    private long homeProject;
    private long otherProject;

    @BeforeEach
    void seedProjects() {
        homeProject = newProject("home");
        otherProject = newProject("other");
    }

    @AfterEach
    void purgeFixtures() {
        if (!caseIds.isEmpty()) {
            String ids = String.join(",", caseIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id IN (" + ids + ")");
            jdbcTemplate.update("DELETE FROM tp_test_case WHERE id IN (" + ids + ")");
        }
        for (Long id : elementIds) {
            jdbcTemplate.update("DELETE FROM tp_web_element WHERE id = ?", id);
        }
        for (Long id : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", id);
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", id);
        }
        caseIds.clear();
        elementIds.clear();
        projectIds.clear();
    }

    private long newProject(String label) {
        long tag = SEQ.incrementAndGet();
        String code = "RCQ" + System.currentTimeMillis() % 100000L + tag + label;
        long id = projectService.create(1L, new ProjectCreateInput(
                "引用清单读侧项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private long newElement(long projectId, String name, String pageKey) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_web_element (project_id, name, page_key) VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, name, pageKey);
        elementIds.add(id);
        return id;
    }

    private long newCase(long projectId, String title, int version) {
        long tag = SEQ.incrementAndGet();
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_test_case (case_no, project_id, title, version) "
                        + "VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "TC-RCQ-" + System.currentTimeMillis() % 1000000L + "-" + tag,
                projectId, title, version);
        caseIds.add(id);
        return id;
    }

    private void newStep(long caseId, int caseVersion, int stepNo, String anchorJson) {
        jdbcTemplate.update(
                "INSERT INTO tp_case_step (case_id, case_version, step_no, description, anchor) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb)",
                caseId, caseVersion, stepNo, "点击 " + stepNo, anchorJson);
    }

    private static String elemRef(String name) {
        return "{\"kind\":\"elem\",\"ref\":\"" + name + "\"}";
    }

    @Test
    void singleRefFormIsListed() {
        long elementId = newElement(homeProject, "登录按钮", "/login");
        long caseId = newCase(homeProject, "登录冒烟", 1);
        newStep(caseId, 1, 2, elemRef("登录按钮"));

        ReferencingCasePage page = query.byElement(elementId, 1, 20);

        assertEquals("登录按钮", page.elementName());
        assertEquals(homeProject, page.projectId());
        assertEquals(1, page.total());
        ReferencingCaseView row = page.items().get(0);
        assertEquals(caseId, row.caseId());
        assertEquals(2, row.stepNo());
        assertEquals("登录冒烟", row.caseTitle());
    }

    @Test
    void refsArrayFormMatchesMemberNameOnly() {
        long elementId = newElement(homeProject, "搜索框", "/home");
        long memberCase = newCase(homeProject, "命中成员名", 1);
        newStep(memberCase, 1, 1, "{\"kind\":\"elem\",\"refs\":[\"搜索框\",\"导航菜单\"]}");
        long otherCase = newCase(homeProject, "不相关成员", 1);
        newStep(otherCase, 1, 1, "{\"kind\":\"elem\",\"refs\":[\"导航菜单\"]}");

        ReferencingCasePage page = query.byElement(elementId, 1, 20);

        assertEquals(1, page.total());
        assertEquals(memberCase, page.items().get(0).caseId());
    }

    @Test
    void otherAnchorKindsAreNotElementReferences() {
        long elementId = newElement(homeProject, "导出按钮", "/report");
        long caseId = newCase(homeProject, "原文证据同名", 1);
        newStep(caseId, 1, 1, "{\"kind\":\"source\",\"ref\":\"导出按钮\"}");
        newStep(caseId, 1, 2, "{\"kind\":\"contract\",\"refs\":[\"导出按钮\"]}");

        ReferencingCasePage page = query.byElement(elementId, 1, 20);

        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void listIsProjectSliced() {
        long elementId = newElement(homeProject, "保存按钮", "/form");
        long foreignCase = newCase(otherProject, "他项目同名用例", 1);
        newStep(foreignCase, 1, 1, elemRef("保存按钮"));

        assertEquals(0, query.byElement(elementId, 1, 20).total());
    }

    @Test
    void softDeletedCasesAndStaleVersionsAreExcluded() {
        long elementId = newElement(homeProject, "删除入口", "/admin");
        long deletedCase = newCase(homeProject, "已删除用例", 1);
        newStep(deletedCase, 1, 1, elemRef("删除入口"));
        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", deletedCase);

        long editedCase = newCase(homeProject, "改版用例", 2);
        newStep(editedCase, 1, 1, elemRef("删除入口"));
        newStep(editedCase, 2, 1, elemRef("别的元素"));

        ReferencingCasePage page = query.byElement(elementId, 1, 20);

        assertEquals(0, page.total());
    }

    @Test
    void sameNameAcrossPagesUnionsByElementName() {
        long first = newElement(homeProject, "用户名输入框", "/login");
        long second = newElement(homeProject, "用户名输入框", "/register");
        long caseId = newCase(homeProject, "账号注册", 1);
        newStep(caseId, 1, 1, elemRef("用户名输入框"));

        // The anchor ledger stores names only, so both same-named ledger rows
        // honestly answer the same name-union list (rename-chain semantics).
        assertEquals(1, query.byElement(first, 1, 20).total());
        assertEquals(1, query.byElement(second, 1, 20).total());
    }

    @Test
    void pagingIsBoundedAndClamped() {
        long elementId = newElement(homeProject, "分页元素", "/list");
        for (int i = 1; i <= 3; i++) {
            long caseId = newCase(homeProject, "分页用例" + i, 1);
            newStep(caseId, 1, 1, elemRef("分页元素"));
        }

        ReferencingCasePage secondPage = query.byElement(elementId, 2, 2);
        assertEquals(3, secondPage.total());
        assertEquals(1, secondPage.items().size());

        assertEquals(100, query.byElement(elementId, 1, 5000).pageSize());
        assertEquals(20, query.byElement(elementId, 1, 0).pageSize());
    }

    @Test
    void unknownElementAnswersEmptyPage() {
        ReferencingCasePage page = query.byElement(999_999_999L, 1, 20);
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
        assertNull(page.projectId());
    }
}
