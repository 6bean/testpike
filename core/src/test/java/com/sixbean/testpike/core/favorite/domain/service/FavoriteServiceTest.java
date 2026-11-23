package com.sixbean.testpike.core.favorite.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.favorite.api.dto.FavoriteItem;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.search.api.dto.SearchResults;
import com.sixbean.testpike.core.search.domain.service.SearchService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class FavoriteServiceTest {

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private long owner;

    /**
 * R37 fixture-scoped teardown : favorites are keyed
 * by user (tp_favorite.user_id FK), so the p4_% user family goes down
 * through the generic user teardown — favorites, roles and notifications
 * with it. The P4* project family carries cases, steps, links,
 * memberships and audit rows reachable from the project sweep. tp_user
 * itself is never truncated so the shipped admin seed survives. The old
 * blanket TRUNCATE wiped every other suite's and concurrent chain's rows
 * on the shared dev database.
 */
    @BeforeEach
    void cleanAndSeed() {
        sweepFixture();
        owner = insertUser("p4_owner");
    }

    @AfterEach
    void sweepOut() {
        sweepFixture();
    }

    private void sweepFixture() {
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "p4_%");
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'P4%'", Long.class));
    }

    
    private long createProject(String code) {
        long pid = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", pid, owner);
        return pid;
    }

    private long createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                2, 2, "web", 1, 1, 1, null, List.of(new CaseStepInput(1, "步骤1", 0)))).id();
    }

    private long insertUser(String username) {
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username = ?)", username);
        jdbcTemplate.update("DELETE FROM tp_user WHERE username = ?", username);
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        return uid != null ? uid : -1L;
    }

    private long countFavorites(long userId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_favorite WHERE user_id = ?", Long.class, userId);
        return c == null ? 0 : c;
    }


    @Test
    void favoriteRepeatIsIdempotentSingleRow() {
        long p = createProject("P4A");
        long caseId = createCase(p, "登录用例");
        favoriteService.favorite(owner, "case", caseId);
        favoriteService.favorite(owner, "case", caseId);
        favoriteService.favorite(owner, "case", caseId);
        assertEquals(1, countFavorites(owner), "重复 PUT 幂等=单行（IT-P4-01）");
        List<FavoriteItem> list = favoriteService.listFavorites(owner, false, "case");
        assertEquals(1, list.size());
        assertEquals("登录用例", list.get(0).title());
        assertEquals("case", list.get(0).entityType());
        assertEquals(caseId, list.get(0).entityId());
        assertTrue(list.get(0).subtitle() != null && list.get(0).subtitle().startsWith("TC-P4A"),
                "用例副标题=case_no");
    }

    @Test
    void unfavoriteReturnsAffectedRows() {
        long p = createProject("P4B");
        long caseId = createCase(p, "登出用例");
        favoriteService.favorite(owner, "case", caseId);
        assertEquals(1, favoriteService.unfavorite(owner, "case", caseId), "首次取消 affected=1");
        assertEquals(0, favoriteService.unfavorite(owner, "case", caseId), "重复取消 affected=0 不报错");
        assertTrue(favoriteService.listFavorites(owner, false, null).isEmpty());
    }

    @Test
    void listOrderByFavoritedAtDescAndFiltersByType() {
        long p = createProject("P4C");
        long case1 = createCase(p, "用例一");
        long case2 = createCase(p, "用例二");
        favoriteService.favorite(owner, "case", case1);
        favoriteService.favorite(owner, "case", case2);
        favoriteService.favorite(owner, "project", p);
        List<FavoriteItem> all = favoriteService.listFavorites(owner, false, null);
        assertEquals(3, all.size());
        assertEquals("project", all.get(0).entityType(), "最新收藏在前（id DESC）");
        assertEquals(case2, all.get(1).entityId());
        List<FavoriteItem> cases = favoriteService.listFavorites(owner, false, "case");
        assertEquals(2, cases.size());
        assertTrue(cases.stream().allMatch(f -> "case".equals(f.entityType())),
                "类型筛选只返回该类型");
    }


    @Test
    void exceedingLimit500ThrowsHumanMessage() {
        for (int i = 1; i <= 500; i++) {
            favoriteService.favorite(owner, "case", 900000L + i);
        }
        assertEquals(500, countFavorites(owner));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> favoriteService.favorite(owner, "case", 999999L));
        assertTrue(ex.getMessage().contains("500"), "上限提示人话含数字：" + ex.getMessage());
        assertEquals(500, countFavorites(owner), "超限请求不落库");
    }

    @Test
    void limitNotTriggeredByIdempotentRepeat() {
        for (int i = 1; i <= 500; i++) {
            favoriteService.favorite(owner, "case", 900000L + i);
        }
        favoriteService.favorite(owner, "case", 900001L);
        assertEquals(500, countFavorites(owner));
    }

    @Test
    void invalidEntityTypeRejected() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> favoriteService.favorite(owner, "widget", 1L));
        assertTrue(ex.getMessage().contains("收藏类型"), "非法类型 400 人话：" + ex.getMessage());
        assertThrows(ServiceException.class, () -> favoriteService.unfavorite(owner, "", 1L));
    }

    @Test
    void invisibleEntityFavoritsButNotListed() {
        long pa = projectService.create(1L,
                new ProjectCreateInput("项目P4Secret", "P4Secret", "zh-CN", "P1", null));
        long caseA = createCase(pa, "内部机密用例");
        long outsider = insertUser("p4_outsider");
        favoriteService.favorite(outsider, "case", caseA);
        assertEquals(0, favoriteService.listFavorites(outsider, false, "case").size(),
                "H1：非可见实体不出现在列表");
        assertEquals(1, favoriteService.listFavorites(outsider, true, "case").size(),
                "allVisible=系统管理员可列表");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", pa, outsider);
        assertEquals(1, favoriteService.listFavorites(outsider, false, "case").size(),
                "加入项目后同一收藏恢复列表可见");
    }

    @Test
    void softDeletedCaseNotListed() {
        long p = createProject("P4Del");
        long caseId = createCase(p, "将被删除的用例");
        favoriteService.favorite(owner, "case", caseId);
        assertEquals(1, favoriteService.listFavorites(owner, false, "case").size());
        caseService.delete(1L, caseId);
        assertEquals(0, favoriteService.listFavorites(owner, false, "case").size(),
                "软删用例（回收站中）不列表，收藏行保留（还原后重现）");
        assertEquals(1, countFavorites(owner), "收藏行不清除（可还原重现）");
    }


    @Test
    void globalSearchRanksFavoritedFirst() {
        long p = createProject("P4W");
        long c1 = createCase(p, "加权搜索甲");
        long c2 = createCase(p, "加权搜索乙");
        SearchResults before = searchService.search(owner, false, "加权搜索");
        assertEquals(c2, before.cases().get(0).id(), "未收藏=按 id DESC");
        favoriteService.favorite(owner, "case", c1);
        SearchResults after = searchService.search(owner, false, "加权搜索");
        assertEquals(c1, after.cases().get(0).id(), "O1 加权：收藏实体 score 提升排前");
        long other = insertUser("p4_other");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 2)", p, other);
        SearchResults r = searchService.search(other, false, "加权搜索");
        assertEquals(c2, r.cases().get(0).id(), "收藏加权按用户隔离");
    }
}
