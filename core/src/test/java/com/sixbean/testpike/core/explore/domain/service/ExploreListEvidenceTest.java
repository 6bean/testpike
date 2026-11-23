package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreSessionView;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreStepView;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExploreListEvidenceTest {

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private long projectId;

    @BeforeEach
    void cleanAndAnchor() {
        // Fixture-scoped teardown (R37): sweep only this test's own project
        // subtree — blanket table deletes on the shared dev database destroy
        // concurrent chains' in-flight rows (r13 postmortem).
        // EXP-T209O is the cross-project fixture created inside the list
        // test — sweep its leftover here as well, or a re-run collides.
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXP-T209L"),
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXP-T209O"));
        projectId = projectService.create(1L,
                new ProjectCreateInput("探索项目", "EXP-T209L", "zh-CN", "P1", null));
        configCacheHolder.put("ai.explore.max-steps", "30");
        configCacheHolder.put("ai.explore.max-pages", "10");
    }

    
    @Test
    void listPaginatesByIdDescAndClampsSize() {
        for (int i = 1; i <= 3; i++) {
            exploreService.create(1L, projectId,
                    new ExploreCreateInput("目标" + i, "http://localhost:5173/p" + i));
        }
        PageResult<ExploreSessionView> p1 = exploreService.list(projectId, 1, 2);
        assertEquals(3L, p1.total(), "total=项目全量会话数");
        assertEquals(2, p1.list().size());
        long first = p1.list().get(0).getId();
        long second = p1.list().get(1).getId();
        assertTrue(first > second, "列表必须 id DESC 稳定序，实际=" + first + "," + second);

        PageResult<ExploreSessionView> p2 = exploreService.list(projectId, 2, 2);
        assertEquals(1, p2.list().size(), "第二页剩余 1 条");
        assertFalse(p2.list().get(0).getId().equals(first) || p2.list().get(0).getId().equals(second),
                "分页不得与第一页重叠");

        assertThrows(ValidationException.class, () -> exploreService.list(projectId, 1, 5000),
                "K13/TC-K13-02：explore 列表非白名单端点，size=5000 必须拒 40001 而非钳 1000");

        long otherProject = projectService.create(1L,
                new ProjectCreateInput("他项目", "EXP-T209O", "zh-CN", "P1", null));
        PageResult<ExploreSessionView> foreign = exploreService.list(otherProject, 1, 20);
        assertEquals(0L, foreign.total(), "跨项目列表必须隔离");
    }

    
    @Test
    void stepsReturnedInSeqAscendingOrder() {
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清登录流程", "http://localhost:5173"));
        exploreService.recordStep(id, new ExploreStepInput(2,
                "{\"action\":\"click\",\"target\":\"登录\"}", "{\"ok\":true}", null, null, "第二步", null));
        exploreService.recordStep(id, new ExploreStepInput(1,
                "{\"action\":\"goto\",\"url\":\"http://localhost:5173\"}", "{\"ok\":true}", null, null, "先看首页", null));

        List<ExploreStepView> steps = exploreService.steps(id);
        assertEquals(2, steps.size());
        assertEquals(1, steps.get(0).getSeq(), "seq 升序：1 在前");
        assertEquals(2, steps.get(1).getSeq());
        assertTrue(steps.get(0).getStepAction().contains("goto"), "stepAction=JSONB 原文回读");
        assertEquals("先看首页", steps.get(0).getLlmThinkMd());
    }

    // screenshotUrl guard: step exists without a screenshot key -> 40401 with an
    // actionable on-screen entry in the copy (R63 three-element rule replaces the
    @Test
    void screenshotUrlRejectsMissingKeyAndMissingStep() {
        long id = exploreService.create(1L, projectId,
                new ExploreCreateInput("目标", "http://localhost:5173"));
        exploreService.recordStep(id, new ExploreStepInput(1,
                "{\"action\":\"goto\"}", "{\"ok\":true}", null, null, null, null));

        ServiceException noKey = assertThrows(ServiceException.class,
                () -> exploreService.screenshotUrl(id, 1));
        assertEquals(40401, noKey.getCode(), "无截图产物应 40401，实际=" + noKey.getMessage());
        assertTrue(noKey.getMessage().contains("「探索详情」") && noKey.getMessage().contains("「取证」"),
                "文案须点名可操作的屏显入口（R63 三要素），实际=" + noKey.getMessage());

        ServiceException noStep = assertThrows(ServiceException.class,
                () -> exploreService.screenshotUrl(id, 9));
        assertEquals(40401, noStep.getCode(), "步不存在应 40401");
    }

    // ---------- server-side sort over the explore session list ----------

    /**
     * Seed four sessions whose id / goal_text / start_url / status / steps_used /
     * cost_cents orders disagree on purpose (insertion is id-ascending; the
     * UPDATE then assigns 2/3/2/1 statuses, 30/10/20/40 steps, 300/100/200/400
     * cents and goals bbb/aaa/ccc/bbb2). goal_text is the row identity used by
     * the assertions, so every whitelisted key walks a different ORDER BY path
     * and the equal status pair pins the id DESC tie-break.
     */
    private void seedExploreSortFixture() {
        exploreService.create(1L, projectId, new ExploreCreateInput("bbb", "http://localhost:5173/z"));
        exploreService.create(1L, projectId, new ExploreCreateInput("aaa", "http://localhost:5173/x"));
        exploreService.create(1L, projectId, new ExploreCreateInput("ccc", "http://localhost:5173/y"));
        exploreService.create(1L, projectId, new ExploreCreateInput("bbb2", "http://localhost:5173/w"));
        jdbcTemplate.update("""
                UPDATE tp_explore_session SET
                    status = CASE goal_text WHEN 'bbb' THEN 2 WHEN 'aaa' THEN 3
                                            WHEN 'ccc' THEN 2 WHEN 'bbb2' THEN 1 END,
                    steps_used = CASE goal_text WHEN 'bbb' THEN 30 WHEN 'aaa' THEN 10
                                            WHEN 'ccc' THEN 20 WHEN 'bbb2' THEN 40 END,
                    cost_cents = CASE goal_text WHEN 'bbb' THEN 300 WHEN 'aaa' THEN 100
                                            WHEN 'ccc' THEN 200 WHEN 'bbb2' THEN 400 END
                WHERE project_id = ?
                """, projectId);
    }

    private List<String> goalsOf(PageResult<ExploreSessionView> page) {
        return page.list().stream().map(ExploreSessionView::getGoalText).toList();
    }

    /** No sort key keeps the historical id DESC order (the 3-arg legacy call
     * delegates to the same default, zero drift for existing callers). */
    @Test
    void listSortAbsent_keepsHistoricalIdDesc() {
        seedExploreSortFixture();
        List<String> expected = List.of("bbb2", "ccc", "aaa", "bbb");
        assertEquals(expected, goalsOf(exploreService.list(projectId, 1, 20, null, null)));
        assertEquals(expected, goalsOf(exploreService.list(projectId, 1, 20, "", "")));
        assertEquals(expected, goalsOf(exploreService.list(projectId, 1, 20)));
    }

    /** goal_text asc/desc mirror each other over the whole window. */
    @Test
    void listSortGoalText_bothDirections() {
        seedExploreSortFixture();
        assertEquals(List.of("aaa", "bbb", "bbb2", "ccc"),
                goalsOf(exploreService.list(projectId, 1, 20, "goal_text", "asc")));
        assertEquals(List.of("ccc", "bbb2", "bbb", "aaa"),
                goalsOf(exploreService.list(projectId, 1, 20, "goal_text", "desc")));
    }

    /** start_url asc orders by the URL column, disagreeing with id order. */
    @Test
    void listSortStartUrl_ordersByColumn() {
        seedExploreSortFixture();
        // /w(bbb2) < /x(aaa) < /y(ccc) < /z(bbb)
        assertEquals(List.of("bbb2", "aaa", "ccc", "bbb"),
                goalsOf(exploreService.list(projectId, 1, 20, "start_url", "asc")));
    }

    /** status asc groups equal values with the constant id DESC tie-break,
     * so paging stays deterministic under ties regardless of direction. */
    @Test
    void listSortStatus_tiePinnedByIdDesc() {
        seedExploreSortFixture();
        // status 1 (bbb2) first, then the status-2 pair pinned id DESC (ccc > bbb),
        // then status 3 (aaa)
        assertEquals(List.of("bbb2", "ccc", "bbb", "aaa"),
                goalsOf(exploreService.list(projectId, 1, 20, "status", "asc")));
        assertEquals(List.of("aaa", "ccc", "bbb", "bbb2"),
                goalsOf(exploreService.list(projectId, 1, 20, "status", "desc")));
    }

    /** steps_used / cost_cents walk their own columns (neither is id order). */
    @Test
    void listSortStepsAndCost_orderByOwnColumns() {
        seedExploreSortFixture();
        assertEquals(List.of("bbb2", "bbb", "ccc", "aaa"),
                goalsOf(exploreService.list(projectId, 1, 20, "steps_used", "desc")));
        assertEquals(List.of("aaa", "ccc", "bbb", "bbb2"),
                goalsOf(exploreService.list(projectId, 1, 20, "cost_cents", "asc")));
    }

    /** id asc is available and is the exact mirror of the default order. */
    @Test
    void listSortIdAsc_isNotDefaultOrder() {
        seedExploreSortFixture();
        assertEquals(List.of("bbb", "aaa", "ccc", "bbb2"),
                goalsOf(exploreService.list(projectId, 1, 20, "id", "asc")));
    }

    /** Sorting keeps paging consistent: page 2 continues the order, total truthful. */
    @Test
    void listSortAppliesAcrossPages() {
        seedExploreSortFixture();
        var first = exploreService.list(projectId, 1, 2, "goal_text", "asc");
        var second = exploreService.list(projectId, 2, 2, "goal_text", "asc");
        assertEquals(List.of("aaa", "bbb"), goalsOf(first));
        assertEquals(List.of("bbb2", "ccc"), goalsOf(second));
        assertEquals(4L, first.total());
    }

    /** An injection-shaped or off-list key is refused fail-closed with a human
     * 400 naming the whitelist; the rows stay intact afterwards. */
    @Test
    void listSortOffListKey_refusedWithWhitelistHint() {
        seedExploreSortFixture();
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> exploreService.list(projectId, 1, 20, "id; DROP TABLE tp_explore_session", "asc"));
        assertEquals(40001, rejected.getCode());
        assertTrue(rejected.getMessage().contains("goal_text"),
                "报错应点名可选列，实际=" + rejected.getMessage());
        assertEquals(4, goalsOf(exploreService.list(projectId, 1, 20)).size(), "行数据不受影响");
    }
}
