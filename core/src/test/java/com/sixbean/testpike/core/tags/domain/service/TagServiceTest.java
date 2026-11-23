package com.sixbean.testpike.core.tags.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.plans.api.dto.CaseScopeRule;
import com.sixbean.testpike.core.plans.api.dto.PlanCreateInput;
import com.sixbean.testpike.core.plans.api.dto.PlanInfo;
import com.sixbean.testpike.core.plans.domain.service.PlanService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.tags.api.dto.CaseTagSetInput;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.api.dto.TagMergeInput;
import com.sixbean.testpike.core.tags.api.dto.TagRenameInput;
import com.sixbean.testpike.core.tags.api.dto.TagStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class TagServiceTest {

    @Autowired
    private TagService tagService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private PlanService planService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects tracked for the R37 teardown (replaces blanket TRUNCATE). */
    private final List<Long> projectIds = new java.util.ArrayList<>();

    /** Fixture users created by this suite, tracked for teardown. */
    private final List<Long> userIds = new java.util.ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // R37 fixture-scoped teardown: tracked users with their notifications
        // and audit rows first, then the tracked projects with their FK
        // subtree. tp_plan_case carries no FK into tp_exec_plan, so the plans
        // tree purge runs before the project sweep; tp_tag_dict and tp_case_tag
        // are project/case-keyed and reached through the shared sweep. The
        // blanket TRUNCATE this replaced wiped concurrent suites' and shipped
        // factory rows on the shared dev database (tp_executor included).
        SharedDbFixtureSweep.usersDown(jdbcTemplate, userIds);
        userIds.clear();
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, List.of(), projectIds);
    }

    private long createProject(String code) {
        // Same service path and name shape as before (the "project-" name
        // prefix + code), plus the single-source leftover sweep of a crashed
        // run's same-code fixture.
        return SharedDbFixtureSweep.createPlanFixtureProject(projectService, jdbcTemplate,
                List.of(), "项目", code, projectIds);
    }

    private long createCase(long projectId, String title, Integer phase) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, phase, 1, null, List.of()));
        return info.id();
    }

    private long createEnv(long projectId, String name) {
        Long eid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot)
                VALUES (?, ?, 2, ?, 'v1.0.0') RETURNING id
                """, Long.class, projectId, name, "http://tp-local.test");
        return eid;
    }

    
    private long createUser(String username) {
        // Crashed-run leftover under the same fixed username is cleared through
        // the shared user teardown first (unique-key guard), then the fresh row
        // is tracked so the per-hook teardown removes exactly this suite's users.
        SharedDbFixtureSweep.usersDown(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_user WHERE username = ?", Long.class, username));
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = 'test_lead'", Long.class);
        jdbcTemplate.update("INSERT INTO tp_user_role (user_id, role_id) VALUES (?, ?)", uid, roleId);
        userIds.add(uid);
        return uid;
    }

    @Test
    void test_tag_crud_rename_merge_and_conflicts() {
        long pid = createProject("M3A");
        long pidB = createProject("M3AB");

        TagInfo free = tagService.create(1L, new TagCreateInput(pid, "支付核心", 4));
        assertNotNull(free.id());
        assertEquals(4, free.type());
        assertEquals(0, free.caseCount());

        ServiceException dup = assertThrows(ServiceException.class,
                () -> tagService.create(1L, new TagCreateInput(pid, "支付核心", 4)));
        assertTrue(dup.getMessage().contains("已存在"), dup.getMessage());

        TagInfo otherProject = tagService.create(1L, new TagCreateInput(pidB, "支付核心", 4));
        assertNotNull(otherProject.id());

        ServiceException badType = assertThrows(ServiceException.class,
                () -> tagService.create(1L, new TagCreateInput(pid, "坏类型", 9)));
        assertTrue(badType.getMessage().contains("标签类型"), badType.getMessage());

        TagInfo renamed = tagService.rename(1L, free.id(), new TagRenameInput("支付核心-改名"));
        assertEquals("支付核心-改名", renamed.name());
        TagInfo another = tagService.create(1L, new TagCreateInput(pid, "另一个标签", 4));
        ServiceException renameDup = assertThrows(ServiceException.class,
                () -> tagService.rename(1L, free.id(), new TagRenameInput("另一个标签")));
        assertTrue(renameDup.getMessage().contains("已存在"), renameDup.getMessage());

        ServiceException selfMerge = assertThrows(ServiceException.class,
                () -> tagService.merge(1L, new TagMergeInput(free.id(), free.id())));
        assertTrue(selfMerge.getMessage().contains("自身"), selfMerge.getMessage());

        tagService.delete(1L, free.id());
        ServiceException gone = assertThrows(ServiceException.class, () -> tagService.get(free.id()));
        assertTrue(gone.getMessage().contains("标签不存在"), gone.getMessage());
    }

    
    @Test
    void test_visible_tags_aggregate_across_projects() {
        long pidA = createProject("M3V-A");
        long pidB = createProject("M3V-B");
        long pidC = createProject("M3V-C");
        TagInfo a1 = tagService.create(1L, new TagCreateInput(pidA, "甲项目标签", 4));
        TagInfo b1 = tagService.create(1L, new TagCreateInput(pidB, "乙项目标签", 4));
        long c1 = createCase(pidA, "甲项目内用例", 2);
        tagService.setCaseTags(1L, c1, new CaseTagSetInput(pidA, List.of(a1.id())));

        List<TagInfo> visible = tagService.listVisible(List.of(pidB, pidA, pidA));
        assertEquals(2, visible.size(), "重复项目 id 去重后两项目各一标签");
        assertTrue(visible.get(0).projectId() < visible.get(1).projectId(), "按项目升序分组稳定");
        TagInfo gotA = visible.stream().filter(t -> t.id() == a1.id()).findFirst().orElseThrow();
        assertEquals(1L, gotA.caseCount(), "聚合谱下引用用例数仍正确");

        assertTrue(tagService.listVisible(List.of()).isEmpty(), "空域不得返回任何标签");

        TagInfo cTag = tagService.create(1L, new TagCreateInput(pidC, "丙项目标签", 4));
        assertTrue(tagService.listVisible(List.of(pidA)).stream()
                .noneMatch(t -> t.id() == cTag.id()), "未选项目的标签不得混入");
    }

    
    @Test
    void test_visible_stats_aggregate_across_projects() {
        long pidA = createProject("M3S-A");
        long pidB = createProject("M3S-B");
        long c1 = createCase(pidA, "甲项目内用例", 2);
        TagInfo a1 = tagService.create(1L, new TagCreateInput(pidA, "甲项目统计标签", 4));
        tagService.setCaseTags(1L, c1, new CaseTagSetInput(pidA, List.of(a1.id())));
        TagInfo b1 = tagService.create(1L, new TagCreateInput(pidB, "乙项目统计标签", 4));

        List<TagStats> stats = tagService.statsVisible(List.of(pidA, pidB));
        assertEquals(2, stats.size(), "两项目标签都在聚合统计内");
        TagStats sa = stats.stream().filter(s -> s.tagId() == a1.id()).findFirst().orElseThrow();
        TagStats sb = stats.stream().filter(s -> s.tagId() == b1.id()).findFirst().orElseThrow();
        assertEquals(1L, sa.caseCount(), "甲项目标签引用数");
        assertEquals(0L, sb.caseCount(), "零引用标签也如实可见（不隐藏）");
        assertTrue(tagService.statsVisible(List.of()).isEmpty(), "空域不得返回任何统计");
    }

    @Test
    void test_case_multi_tags_idempotent_and_stats() {
        long pid = createProject("M3B");
        long c1 = createCase(pid, "登录功能用例", 2);
        long c2 = createCase(pid, "支付功能用例", 2);
        long c3 = createCase(pid, "回归冒烟用例", 1);

        TagInfo t1 = tagService.create(1L, new TagCreateInput(pid, "功能域", 2));
        TagInfo t2 = tagService.create(1L, new TagCreateInput(pid, "回归集", 4));
        TagInfo t3 = tagService.create(1L, new TagCreateInput(pid, "P0重点", 3));

        List<TagInfo> set1 = tagService.setCaseTags(1L, c1, new CaseTagSetInput(pid, List.of(t1.id(), t3.id())));
        assertEquals(2, set1.size(), "用例 c1 双标签");

        List<TagInfo> set1Again = tagService.setCaseTags(1L, c1, new CaseTagSetInput(pid, List.of(t1.id(), t3.id())));
        assertEquals(2, set1Again.size(), "幂等重设不重复");
        Long linkCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_tag WHERE case_id = ?", Long.class, c1);
        assertEquals(2L, linkCount, "DB 关联 2 条不膨胀");

        tagService.setCaseTags(1L, c1, new CaseTagSetInput(pid, List.of(t2.id())));
        List<TagInfo> after = tagService.caseTags(c1);
        assertEquals(1, after.size());
        assertEquals(t2.id(), after.get(0).id());

        long pidB = createProject("M3BB");
        TagInfo foreign = tagService.create(1L, new TagCreateInput(pidB, "外部标签", 4));
        ServiceException cross = assertThrows(ServiceException.class,
                () -> tagService.setCaseTags(1L, c1, new CaseTagSetInput(pid, List.of(foreign.id()))));
        assertTrue(cross.getMessage().contains("不属于该项目"), cross.getMessage());

        tagService.setCaseTags(1L, c2, new CaseTagSetInput(pid, List.of(t1.id())));
        tagService.setCaseTags(1L, c3, new CaseTagSetInput(pid, List.of(t2.id())));
        jdbcTemplate.update("UPDATE tp_test_case SET last_exec_result = 3 WHERE id = ?", c2);
        List<TagStats> stats = tagService.stats(pid);
        TagStats s1 = stats.stream().filter(s -> s.tagId() == t1.id()).findFirst().orElseThrow();
        assertEquals(1, s1.caseCount());
        assertEquals(1, s1.passCount());
        assertEquals(100.0, s1.passRate(), 0.01, "t1 全通过");
        TagStats s2 = stats.stream().filter(s -> s.tagId() == t2.id()).findFirst().orElseThrow();
        assertEquals(2, s2.caseCount());
        assertEquals(0, s2.passCount(), "t2 均未执行");
        assertEquals(0.0, s2.passRate(), 0.01);
    }

    @Test
    void test_case_list_filter_by_tag_and_h1() {
        long pid = createProject("M3C");
        long pidB = createProject("M3CB");
        long c1 = createCase(pid, "登录", 2);
        long c2 = createCase(pid, "支付", 2);
        createCase(pidB, "其他项目用例", 2);

        TagInfo t1 = tagService.create(1L, new TagCreateInput(pid, "重点回归", 4));
        tagService.setCaseTags(1L, c1, new CaseTagSetInput(pid, List.of(t1.id())));
        tagService.setCaseTags(1L, c2, new CaseTagSetInput(pid, List.of(t1.id())));
        long operatorId = createUser("m3user");

        PageResult<CaseInfo> hit = caseService.list(
                new com.sixbean.testpike.core.cases.api.dto.CaseQuery(
                        pid, null, null, null, null, null, null, List.of(t1.id()), null, null, 1, 10),
                operatorId);
        assertEquals(2, hit.total(), "标签命中 2 条");

        TagInfo foreign = tagService.create(1L, new TagCreateInput(pidB, "外部", 4));
        PageResult<CaseInfo> cross = caseService.list(
                new com.sixbean.testpike.core.cases.api.dto.CaseQuery(
                        pid, null, null, null, null, null, null, List.of(foreign.id()), null, null, 1, 10),
                operatorId);
        assertEquals(0, cross.total(), "跨项目标签不命中");

        PageResult<CaseInfo> all = caseService.list(
                new com.sixbean.testpike.core.cases.api.dto.CaseQuery(
                        pid, null, null, null, null, null, null, List.of(), null, null, 1, 10),
                operatorId);
        assertEquals(2, all.total());
    }

    @Test
    void test_plan_scope_tag_rule_and_delete_protection() {
        long pid = createProject("M3D");
        long c1 = createCase(pid, "支付-标签内", 3);
        long c2 = createCase(pid, "支付-无标签", 3);
        TagInfo t1 = tagService.create(1L, new TagCreateInput(pid, "回归范围", 4));
        tagService.setCaseTags(1L, c1, new CaseTagSetInput(pid, List.of(t1.id())));
        long env1 = createEnv(pid, "测试环境");

        PlanInfo plan = planService.create(1L, new PlanCreateInput(pid, "标签范围计划", "v2.0",
                null, List.of(env1), CaseScopeRule.of(List.of(3), List.of(), null, List.of(t1.id())),
                null, null, null, null, 1));
        Long pulled = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_plan_case WHERE plan_id = ?", Long.class, plan.id());
        assertEquals(1L, pulled, "计划仅拉入带标签用例");

        ServiceException protectedTag = assertThrows(ServiceException.class,
                () -> tagService.delete(1L, t1.id()));
        assertTrue(protectedTag.getMessage().contains("已被 1 条用例引用"), protectedTag.getMessage());

        TagInfo t2 = tagService.create(1L, new TagCreateInput(pid, "合并目标", 4));
        tagService.merge(1L, new TagMergeInput(t1.id(), t2.id()));
        Long migrated = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_tag WHERE tag_id = ?", Long.class, t2.id());
        assertEquals(1L, migrated, "关联迁移到目标标签");
        ServiceException gone = assertThrows(ServiceException.class, () -> tagService.get(t1.id()));
        assertTrue(gone.getMessage().contains("标签不存在"), gone.getMessage());
    }

    // ── Server-side sorting through a physical-column whitelist ──

    @Test
    void test_list_sorts_by_whitelisted_columns_both_directions() {
        long pid = createProject("TSORT1");
        // Insertion order (zz, aa, mm) deliberately differs from BOTH name orders,
        // so the id-ASC default can never accidentally satisfy either assertion.
        TagInfo zz = tagService.create(1L, new TagCreateInput(pid, "zz_sort", 3));
        TagInfo aa = tagService.create(1L, new TagCreateInput(pid, "aa_sort", 2));
        TagInfo mm = tagService.create(1L, new TagCreateInput(pid, "mm_sort", 1));

        // case_count is a derived alias column: give the three tags 0/1/2 references
        // so numeric sorting of the aggregate differs from every other order.
        long c1 = createCase(pid, "甲用例", 2);
        long c2 = createCase(pid, "乙用例", 2);
        tagService.setCaseTags(1L, c1, new CaseTagSetInput(pid, List.of(zz.id())));
        tagService.setCaseTags(1L, c2, new CaseTagSetInput(pid, List.of(zz.id(), aa.id())));

        List<TagInfo> asc = tagService.list(pid, "name", "asc");
        assertEquals(List.of("aa_sort", "mm_sort", "zz_sort"),
                asc.stream().map(TagInfo::name).toList(), "name asc must reorder server-side");

        List<TagInfo> desc = tagService.list(pid, "name", "desc");
        assertEquals(List.of("zz_sort", "mm_sort", "aa_sort"),
                desc.stream().map(TagInfo::name).toList(), "name desc mirrors asc");

        List<TagInfo> byType = tagService.list(pid, "type", "asc");
        assertEquals(List.of(1, 2, 3),
                byType.stream().map(TagInfo::type).toList(), "type column sorts numerically");

        List<Long> byCaseCount = tagService.list(pid, "case_count", "desc").stream()
                .map(TagInfo::caseCount).toList();
        assertEquals(List.of(2L, 1L, 0L), byCaseCount, "case_count alias sorts by aggregate value");

        // Default order (no sort requested) keeps the historical id-ASC behaviour.
        List<TagInfo> dflt = tagService.list(pid, null, null);
        assertEquals(List.of(zz.id(), aa.id(), mm.id()),
                dflt.stream().map(TagInfo::id).toList(), "default stays id ASC");
    }

    @Test
    void test_list_sort_by_injection_shaped_key_rejected_400() {
        long pid = createProject("TSORT2");
        tagService.create(1L, new TagCreateInput(pid, "对照标签", 4));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> tagService.list(pid, "id OR 1=1", "asc"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("不在白名单"), ex.getMessage());
    }
}
