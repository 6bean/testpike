package com.sixbean.testpike.core.search.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.requirement.api.dto.RequirementCreateInput;
import com.sixbean.testpike.core.requirement.domain.service.RequirementService;
import com.sixbean.testpike.core.search.api.dto.SearchItem;
import com.sixbean.testpike.core.search.api.dto.SearchResults;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Global search suite (O1, docs 17 + 18 /02).
 * <p>Runs against the real shared dev database, no mocks. Verifies:
 * keyword classification across cases/requirements/defects/projects;
 * H1 isolation (rows of invisible projects never surface) + literal
 * special-character matching;
 * empty-result categories + over-length keyword truncation.
 * Contract: GET /search?keyword= caps every category at 10 rows (doc 02 O1).
 *
 * <p>Shared-database discipline (R37): no blanket TRUNCATE. Every fixture row
 * carries a per-run token so keyword counts are run-exclusive, results are
 * cross-checked against the tp_test_case id watermark, and teardown deletes
 * only this suite's tracked projects plus its own o1_-prefixed users.
 */
@SpringBootTest(classes = CoreApplication.class)
class SearchServiceTest {

    @Autowired
    private SearchService searchService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private com.sixbean.testpike.core.cases.domain.service.CaseService caseService;

    @Autowired
    private RequirementService requirementService;

    @Autowired
    private DefectLibraryService defectLibraryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture project codes created by this suite (swept id-first). */
    private final List<Long> projectIds = new ArrayList<>();

    /** Per-run name token: keyword hits cannot collide with factory rows. */
    private String token;

    /** tp_test_case id watermark: rows above it belong to this run. */
    private long caseFloor;

    @BeforeEach
    void openFixture() {
        token = Long.toHexString(ThreadLocalRandom.current().nextLong(0x10000000L, 0xFFFFFFF0L));
        caseFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_test_case");
        // Crash-residue guard: a killed round leaves rows under this suite's
        // code family; the project-code uniqueness rule would then reject the
        // re-created fixture, and stale titles would pollute keyword counts.
        // Equality sweep of the family (codes O1s*, matched by prefix, never
        // present in the shipped baseline) removes them child-first.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, familyProjectIds());
        sweepUserFamily();
    }

    @AfterEach
    void sweepFixtureRows() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
        projectIds.clear();
        sweepUserFamily();
    }

    private List<Long> familyProjectIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'O1s%'", Long.class);
    }

    /**
     * Users the suite creates itself (o1_ prefix). tp_user is never truncated:
     * the shipped admin seed must survive, and leftover non-empty rows are what
     * UserSeedInitializer's "seed only when empty" guard checks, which would
     * keep core restarts from re-creating admin (L4/E2E logins 401). Role and
     * membership links go first because their FKs block the user delete.
     */
    private void sweepUserFamily() {
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'o1_%')");
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'o1_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'o1_%'");
    }

    /** This run's name/code token; suffix keeps multi-project tests distinct. */
    private String fam(String suffix) {
        return "O1s" + token + suffix;
    }

    private long createProject(long ownerUserId, String code) {
        long id = projectService.create(ownerUserId,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private void createCase(long projectId, String title) {
        caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                2, 2, "web", 1, 1, 1, null, List.of(new CaseStepInput(1, "步骤1", 0))));
    }

    private void createRequirement(long projectId, String title) {
        requirementService.create(1L, new RequirementCreateInput(projectId, 3, "REQ-1", title, "验收", null));
    }

    private void createDefect(long projectId, String title) {
        defectLibraryService.create(1L, new DefectCreateInput(projectId, null, null, title,
                "描述", 2, null, null, null));
    }

    /** Watermark cross-check: every returned case id was created after open. */
    private void assertRunScoped(List<SearchItem> items) {
        for (SearchItem item : items) {
            assertTrue(item.id() > caseFloor,
                    "命中行 id=" + item.id() + " 低于本轮水位 " + caseFloor + "，非本轮 fixture");
        }
    }

    // ── Backend support: keyword classifies into four categories ────────────

    @Test
    void keywordClassifiesFourCategories() {
        String fam = fam("");
        long p = createProject(1L, fam);
        createCase(p, fam + "登录用例");
        createRequirement(p, fam + "需求点");
        createDefect(p, fam + "缺陷单");
        // Project classification matches by name (the code embedded in the
        // name) or by code; user 1 = creator/owner sees it without allVisible.
        SearchResults r = searchService.search(1L, false, fam);
        assertEquals(1, r.cases().size(), "用例分类应命中");
        assertRunScoped(r.cases());
        assertTrue(r.cases().get(0).title().contains(fam + "登录用例"));
        assertEquals(1, r.requirements().size(), "需求点分类应命中");
        assertEquals(1, r.defects().size(), "缺陷分类应命中");
        assertEquals(1, r.projects().size(), "项目分类应命中 name");
        assertEquals("项目" + fam, r.projects().get(0).title());
        // subtitle: case=case_no, requirement=source_id, defect=external or null,
        // project=code.
        assertTrue(r.cases().get(0).subtitle().startsWith("TC-" + fam), "用例副标题应为 case_no");
        assertEquals(fam, r.projects().get(0).subtitle(), "项目副标题应为 code");
    }

    @Test
    void caseNoMatchesInGlobalSearch() {
        String fam = fam("");
        long p = createProject(1L, fam);
        createCase(p, "与关键字无关的标题");
        // case_no is generated per project (TC-<code>-001), keyword uses the number fragment.
        SearchResults r = searchService.search(1L, true, "TC-" + fam + "-001");
        assertEquals(1, r.cases().size(), "全局搜索应命中 case_no");
        assertRunScoped(r.cases());
    }

    @Test
    void eachCategoryLimitedToTen() {
        String fam = fam("");
        long p = createProject(1L, fam);
        for (int i = 1; i <= 12; i++) {
            createCase(p, fam + "Crowd用例" + i);
        }
        SearchResults r = searchService.search(1L, true, fam);
        assertEquals(10, r.cases().size(), "每类上限 10 条（契约 02 §2.1 O1）");
        assertRunScoped(r.cases());
    }

    // ── H1 isolation + literal special characters ────────────────────────────

    @Test
    void invisibleProjectDataExcluded() {
        String fam = fam("");
        long pa = createProject(1L, fam + "A");   // owner user 1
        long pb = createProject(1L, fam + "B");
        createCase(pa, fam + "SecretAlpha用例");
        createCase(pb, fam + "SecretBeta用例");
        long outsider = insertUser("o1_outsider");
        long memberB = insertUser("o1_memberB");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", pb, memberB);

        // outsider belongs to no project → every category empty
        SearchResults none = searchService.search(outsider, false, fam);
        assertTrue(none.cases().isEmpty() && none.projects().isEmpty(), "非成员不可见任何数据（H1）");

        // memberB is only in project B → only the Beta case + project B
        SearchResults r = searchService.search(memberB, false, fam);
        assertEquals(1, r.cases().size(), "仅返回可见项目用例");
        assertRunScoped(r.cases());
        assertTrue(r.cases().get(0).title().contains("Beta"));
        assertEquals(1, r.projects().size(), "项目分类仅返回可见项目");
        assertEquals(fam + "B", r.projects().get(0).subtitle());
    }

    @Test
    void specialCharsLiteralNotWildcard() {
        String fam = fam("");
        long p = createProject(1L, fam);
        createCase(p, "覆盖率" + fam + "100%达标");
        createCase(p, "覆盖率" + fam + "90%达标");
        // Decoys that only a broken escape (wildcard reading) would swallow:
        // the digit-suffixed case matches when % degrades to one-character
        // wildcard, and the letter-prefixed requirement matches when _ does.
        createCase(p, "覆盖率" + fam + "1001达标");
        createRequirement(p, "含" + fam + "_下划线需求");
        createRequirement(p, fam + "a下划线需求");
        SearchResults r = searchService.search(1L, true, fam + "100%");
        assertEquals(1, r.cases().size(), "% 应字面匹配非通配（ILIKE ESCAPE）");
        assertRunScoped(r.cases());
        SearchResults r2 = searchService.search(1L, true, fam + "_下");
        assertEquals(1, r2.requirements().size(), "_ 应字面匹配非单字符通配");
    }

    // ── Empty keyword / over-length truncation ───────────────────────────────

    @Test
    void emptyKeywordReturnsAllVisibleCategories() {
        String fam = fam("");
        long p = createProject(1L, fam);
        createCase(p, fam + "G域用例甲");
        // Empty/blank keyword = first 10 of every category inside the visible
        // scope (same semantics as the list keyword). The viewer is a dedicated
        // member of only this project, so the shared database's factory rows
        // never enter its visible set.
        long viewer = insertUser("o1_viewer");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", p, viewer);
        SearchResults r = searchService.search(viewer, false, "  ");
        assertEquals(1, r.cases().size());
        assertRunScoped(r.cases());
        assertEquals(1, r.projects().size());
    }

    @Test
    void noMatchReturnsEmptyCategories() {
        String fam = fam("");
        long p = createProject(1L, fam);
        createCase(p, "存在的用例");
        SearchResults r = searchService.search(1L, true, fam + "XYZ不存在关键字");
        assertTrue(r.cases().isEmpty() && r.requirements().isEmpty()
                && r.defects().isEmpty() && r.projects().isEmpty(), "空结果四分类均为空列表非 null");
    }

    @Test
    void keywordTooLongTruncated() {
        String fam = fam("");
        long p = createProject(1L, fam);
        String head50 = "超长关键字前缀ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGH";
        createCase(p, fam + head50 + "后缀");
        SearchResults r = searchService.search(1L, true, fam + head50 + "后缀" + "x".repeat(100));
        assertEquals(1, r.cases().size(), "超长截断 ≤50 后仍命中前缀（不报错）");
        assertRunScoped(r.cases());
    }

    private long insertUser(String username) {
        // Pre-delete the same fixed name before inserting (crash-residue guard;
        // tp_user is never truncated so the shipped admin seed survives).
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username = ?)", username);
        jdbcTemplate.update("DELETE FROM tp_user WHERE username = ?", username);
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        return uid != null ? uid : -1L;
    }
}
