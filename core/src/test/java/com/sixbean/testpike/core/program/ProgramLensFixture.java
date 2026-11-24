package com.sixbean.testpike.core.program;

import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.program.domain.service.ProgramMemberService;
import com.sixbean.testpike.core.program.domain.service.ProgramService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.FixtureInserts;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared fixture for every program-lens test family (project-set lens foundation).
 *
 * <p>Single source for building and tearing down lens fixtures through the services
 * under test: fresh projects, fresh programs, group grants and cleanup all live here
 * — test classes must not re-implement row construction. Wiring is supplied and torn
 * down by ProgramLensFixtureExtension so per-class setup code is not duplicated either.
 *
 * <p>Ground rules: shipped tables are never truncated; teardown deletes only the
 * recorded fixture rows. tp_audit_log is append-only (trigger blocks DELETE by
 * design), so each case gets fresh ids and audit-count assertions never read stale rows.
 */
public final class ProgramLensFixture {

    /** Fixture operator account (creator of lens rows; implicit owner of new projects). */
    public static final long OPERATOR = 5L;

    private final ProjectService projectService;
    private final MemberService memberService;
    private final ProgramService programService;
    private final ProgramMemberService programMemberService;
    private final JdbcTemplate jdbcTemplate;

    private final List<Long> programIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();

    /** Synthetic user id space kept away from real accounts; bump per fixture instance. */
    private long userSeq = 771000L;

    /** Real tp_user rows created through testLeadUser(), torn down by cleanup(). */
    private final List<Long> realUserIds = new ArrayList<>();

    public ProgramLensFixture(ProjectService projectService, MemberService memberService,
                              ProgramService programService,
                              ProgramMemberService programMemberService,
                              JdbcTemplate jdbcTemplate) {
        this.projectService = projectService;
        this.memberService = memberService;
        this.programService = programService;
        this.programMemberService = programMemberService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public long freshUser() {
        return ++userSeq;
    }

    /**
     * Real tp_user row linked to the test_lead role (data scope 2 = project-wide).
     *
     * <p>Single source for every lens test family — domain tests must not copy this
     * construction (R40). Test operation requires a genuine user row because
     * dataScopeOfUser throws for missing ones; a role-less user would fall into the
     * self-filter data scope and skew list counts. Torn down by cleanup().
     */
    public long testLeadUser() {
        String name = "lens-user-" + UUID.randomUUID().toString().substring(0, 8);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, 'x', ?, 1, 1) RETURNING id",
                Long.class, name, name);
        Long leadRoleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = 'test_lead'", Long.class);
        jdbcTemplate.update("INSERT INTO tp_user_role (user_id, role_id) VALUES (?, ?)",
                id, leadRoleId);
        realUserIds.add(id);
        return id;
    }

    /** Active project created through the real service (creator gets the implicit owner row). */
    public long newProject() {
        ensureOperatorRow();
        String code = "PX" + UUID.randomUUID().toString().substring(0, 8);
        // default_priority stays "P1" (column is varchar(8); the code value belongs to `code`)
        long id = projectService.create(OPERATOR,
                new ProjectCreateInput("lens-fixture", code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    public long newProgram() {
        ensureOperatorRow();
        long id = programService.create("set-" + UUID.randomUUID().toString().substring(0, 8),
                "fixture program", OPERATOR);
        programIds.add(id);
        return id;
    }

    /**
     * Idempotent re-seed of the fixed operator row (id 5) that every lens family assumes.
     *
     * <p>Root cause guard: legacy suites (e.g. the defect service suite) TRUNCATE tp_user
     * CASCADE, so the seeded operator can vanish mid-suite and cross-class order decides
     * whether lens tests see it (observed live 2026-11-07: same code green in one order,
     * EmptyResultDataAccessException in the other). The proper fix — stopping legacy
     * suites from truncating shipped tables — is an R37 debt tracked separately; this
     * guard only makes the lens family order-independent, never touches shipped data
     * when the row is present.
     */
    private void ensureOperatorRow() {
        Integer present = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user WHERE id = ?", Integer.class, OPERATOR);
        if (present != null && present > 0) {
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO tp_user (id, username, password_hash, display_name, role, status) "
                        + "VALUES (?, 'lens-operator', 'x', 'Lens Operator', 1, 1)", OPERATOR);
    }

    /** Group grant: program includes the project and the user sits in the program. */
    public void grant(long programId, long projectId, long userId, int programRole) {
        programService.attachProject(programId, projectId, OPERATOR);
        programMemberService.add(programId, userId, programRole, OPERATOR);
    }

    /** Direct project membership at the given role (1 member, 2 owner). */
    public void direct(long projectId, long userId, int role) {
        memberService.add(projectId, userId, role);
    }

    /** Standard one-step case draft used by every lens test family (single source, R40). */
    public static CaseCreateInput caseInput(long projectId, String title) {
        return new CaseCreateInput(projectId, title, "前置", "预期", 1, 2, "web", 3, null, 1,
                null, List.of(new CaseStepInput(1, "操作", 0)));
    }

    /** Create a lens and attach the given projects to it; returns the lens id. */
    public long lensWithProjects(long... projectIds) {
        long lens = newProgram();
        for (long projectId : projectIds) {
            programService.attachProject(lens, projectId, OPERATOR);
        }
        return lens;
    }

    /** Three fresh projects plus a lens holding only the first two; the third stays outside. */
    public long[] lensOverTwoOfThreeProjects() {
        long a = newProject();
        long b = newProject();
        long outside = newProject();
        return new long[]{a, b, outside, lensWithProjects(a, b)};
    }

    /**
     * Performance env row owned by the project (tp_project_env is a shipped table; the
     * perf scenario door requires a project-scoped env id). Torn down by cleanup().
     */
    public long perfEnv(long projectId) {
        long seq = userSeq++;
        return FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, ?, 1, 'http://lens-perf.local/api')",
                projectId, "lens-perf-env-" + seq);
    }

    /**
     * Four fresh projects: three carry a perf env each, a lens covers the first two.
     * Returns {a, b, outside, lens, envA, envB, envOutside} for scenario-list lenses.
     */
    public long[] lensOverTwoOfThreePerfProjects() {
        long a = newProject();
        long b = newProject();
        long outside = newProject();
        long lens = lensWithProjects(a, b);
        return new long[]{a, b, outside, lens, perfEnv(a), perfEnv(b), perfEnv(outside)};
    }

    /** Lens over two projects where the user joins only the first; {a, b, lens, member}. */
    public long[] lensWithMemberOfFirstProject() {
        long a = newProject();
        long b = newProject();
        long lens = lensWithProjects(a, b);
        long member = testLeadUser();
        direct(a, member, 1);
        return new long[]{a, b, lens, member};
    }

    /** Data-pool ledger row (tp_perf_dataset anchors on tp_project only) for lens tests. */
    public long perfDataset(long projectId, String name) {
        return FixtureInserts.insertReturningId(jdbcTemplate,
                "INSERT INTO tp_perf_dataset (project_id, name, file_name, object_key, "
                        + "columns_json, row_count, size_bytes) VALUES (?, ?, 'lens.csv', ?, "
                        + "'[\"c1\"]'::jsonb, 1, 10)",
                projectId, name, "lens-fixture/" + name);
    }

    /** Remove every row this fixture created (domain rows, grants, memberships, projects). */
    public void cleanup() {
        // Domain rows anchored on fixture projects go first — foreign keys onto
        // tp_project decide the order (batch links, batches, case steps, cases).
        // Single teardown source for the whole lens family (R40): domain tests
        // must not re-implement these deletes.
        String fixtureProjects = "SELECT id FROM tp_project WHERE name = 'lens-fixture'";
        // Report snapshots anchor on scope_id = project id (added with the report-lens
        // family, fifth domain consumer); global rows stay 0 and are never fixture-made.
        jdbcTemplate.update("DELETE FROM tp_report WHERE scope_type = 1 AND scope_id IN ("
                + fixtureProjects + ")");
        jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN "
                + "(SELECT id FROM tp_exec_batch WHERE project_id IN (" + fixtureProjects + "))");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id IN (" + fixtureProjects + ")");
        // tp_defect holds FKs onto both tp_project and tp_test_case — delete it before
        // the case rows (added with the defect-lens family, third domain consumer).
        jdbcTemplate.update("DELETE FROM tp_defect WHERE project_id IN (" + fixtureProjects + ")");
        // API collections reference tp_project only (parent links stay inside the
        // same statement's delete set, so one statement clears both levels).
        jdbcTemplate.update("DELETE FROM tp_apitest_collection WHERE project_id IN (" + fixtureProjects + ")");
        // Perf scenarios anchor on tp_project AND tp_project_env (added with the
        // perf-lens family, fourth domain consumer) — scenarios retire before their envs.
        // Load-test runs and their second-level frames anchor on tp_perf_run → tp_perf_scenario
        // → tp_project (perf overview block, added to this single teardown source) — frames
        // and runs retire before their scenarios.
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN (" + fixtureProjects + ")");
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE project_id IN (" + fixtureProjects + ")");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN (" + fixtureProjects + ")");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN (" + fixtureProjects + ")");
        jdbcTemplate.update("DELETE FROM tp_perf_dataset WHERE project_id IN (" + fixtureProjects + ")");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN (" + fixtureProjects + ")");
        jdbcTemplate.update("DELETE FROM tp_case_step WHERE case_id IN "
                + "(SELECT id FROM tp_test_case WHERE project_id IN (" + fixtureProjects + "))");
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE project_id IN (" + fixtureProjects + ")");
        // Journey rows anchor on fixture programs (tp_journey.program_id) and fixture
        // projects (segment/run-segment project_id) — clear the four journey tables
        // before programs and projects retire (report-lens sixth consumer, pitfall 14).
        // Id lists expand to one placeholder per id (a single IN (?) against a
        // multi-element varargs fails the argument binding — window evidence).
        String journeyProjects = "SELECT id FROM tp_project WHERE name = 'lens-fixture'";
        String journeyPlaceholders = placeholders(programIds);
        if (!programIds.isEmpty()) {
            Object[] pids = programIds.toArray();
            jdbcTemplate.update("DELETE FROM tp_journey_run_segment WHERE project_id IN (" + journeyProjects + ")"
                    + " OR run_id IN (SELECT r.id FROM tp_journey_run r JOIN tp_journey j ON j.id = r.journey_id"
                    + " WHERE j.program_id IN (" + journeyPlaceholders + "))", pids);
            jdbcTemplate.update("DELETE FROM tp_journey_run WHERE journey_id IN ("
                    + "SELECT id FROM tp_journey WHERE program_id IN (" + journeyPlaceholders + "))", pids);
            jdbcTemplate.update("DELETE FROM tp_journey_segment WHERE project_id IN (" + journeyProjects + ")"
                    + " OR journey_id IN (SELECT id FROM tp_journey WHERE program_id IN ("
                    + journeyPlaceholders + "))", pids);
            jdbcTemplate.update("DELETE FROM tp_journey WHERE program_id IN (" + journeyPlaceholders + ")", pids);
        } else {
            jdbcTemplate.update("DELETE FROM tp_journey_run_segment WHERE project_id IN (" + journeyProjects + ")");
            jdbcTemplate.update("DELETE FROM tp_journey_segment WHERE project_id IN (" + journeyProjects + ")");
        }
        for (long programId : programIds) {
            jdbcTemplate.update("DELETE FROM tp_user_program WHERE program_id = ?", programId);
            jdbcTemplate.update("DELETE FROM tp_program_project WHERE program_id = ?", programId);
        }
        if (!programIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM tp_program WHERE id IN (" + placeholders(programIds) + ")",
                    programIds.toArray());
        }
        for (long projectId : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ?", projectId);
        }
        for (long projectId : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        for (long userId : realUserIds) {
            jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
        }
        realUserIds.clear();
        programIds.clear();
        projectIds.clear();
    }

    private static String placeholders(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.length() == 0 ? "NULL" : sb.toString();
    }
}
