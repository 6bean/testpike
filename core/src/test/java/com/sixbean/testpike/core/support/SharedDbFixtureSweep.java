package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fixture-scoped teardown for the shared dev database (R37 discipline).
 *
 * <p>A blanket {@code TRUNCATE ... CASCADE} on a factory table (tp_project,
 * tp_explore_session, tp_document, ...) wipes every row the table carries —
 * including rows owned by concurrent L3 probes and other chains running on
 * the same dev database. Round-13 postmortem: a second chain's {@code
 * TRUNCATE tp_project CASCADE} inside its per-test cleanup deleted an L3
 * probe's session 4 seconds after creation, and the failure surfaced as a
 * bogus "explore session not found" product error.
 *
 * <p>This helper deletes ONLY the rows reachable from the given fixture
 * project ids, walking the FK graph child-first — the Java twin of
 * {@code tools/verification/_l3_db.py::sweep_project_rows} (same algorithm,
 * one semantic per language side per R40: python for L3 probes, java for L2
 * tests; neither can import the other).
 *
 * <p>Non-FK children keep a project_id column without a constraint into
 * tp_project; the default set below plus the {@code extraProjectTables}
 * argument covers them.
 */
public final class SharedDbFixtureSweep {

    /**
     * Project-keyed rows with no FK into tp_project (live schema scan, 2026-11-02):
     * audit trail, in-site notifications, LLM call logs, exec batches and deletion
     * approval requests. They
     * were part of the blanket TRUNCATE lists this helper replaces, so the
     * fixture sweep removes them per project id by default.
     */
    private static final List<String> DEFAULT_PROJECT_TABLES = List.of(
            "tp_exec_task", "tp_exec_plan", "tp_llm_usage_sample", "tp_audit_log",
            "tp_notification", "tp_ai_call_log", "tp_exec_batch", "tp_defect",
            "tp_document", "tp_doc_change_analysis", "tp_tag_dict",
            "tp_delete_request");

    /**
     * Rows that hang off a test case but declare no FK into tp_test_case, so the
     * generic FK walk in {@link #byProject} cannot reach them: tag links, case
     * versions and script versions. The blanket TRUNCATE lists these helpers
     * replaced cleared them too, so the project sweep purges them explicitly —
     * otherwise they accumulate forever against cases that are long gone.
     */
    private static final List<String> CASE_KEYED_TABLES_WITHOUT_FK =
            List.of("tp_case_tag", "tp_case_version", "tp_script_version");

    private SharedDbFixtureSweep() {
    }

    /** Sweep with no extra non-FK project tables. */
    public static void byProject(JdbcTemplate jdbc, long... projectIds) {
        byProject(jdbc, List.of(), projectIds);
    }

    /**
     * Sweep a tracked fixture-project list (idempotent: an emptied list is a
     * no-op, so suites may call this from both @BeforeEach and @AfterEach).
     */
    public static void byProjectList(JdbcTemplate jdbc, List<Long> projectIds) {
        byProject(jdbc, projectIds.stream().mapToLong(Long::longValue).toArray());
    }

    /** Tracked-project sweep with extra project-keyed (non-FK) tables. */
    public static void byProjectList(JdbcTemplate jdbc, List<String> extraProjectTables,
            List<Long> projectIds) {
        byProject(jdbc, extraProjectTables,
                projectIds.stream().mapToLong(Long::longValue).toArray());
    }

    /**
     * Ids of the projects whose name starts with one of the fixture's fixed
     * prefixes. Name-family suites (the executor claim ratchet) seed projects
     * with constant names on each run, so their teardown resolves the family
     * through this one query instead of re-spelling the LIKE ANY per suite.
     * An empty prefix list yields an empty id list (no-op sweep).
     */
    public static List<Long> projectIdsByNamePrefixes(JdbcTemplate jdbc,
            List<String> namePrefixes) {
        if (namePrefixes.isEmpty()) {
            return List.of();
        }
        String patterns = namePrefixes.stream()
                .map(n -> "'" + n + "%'")
                .collect(Collectors.joining(","));
        return jdbc.queryForList(
                "SELECT id FROM tp_project WHERE name LIKE ANY(ARRAY[" + patterns + "])",
                Long.class);
    }

    /** Name-family entry point onto {@link #byProjectList}: resolve, then sweep. */
    public static void byProjectNameFamily(JdbcTemplate jdbc, List<String> extraProjectTables,
            List<String> namePrefixes) {
        byProjectList(jdbc, extraProjectTables, projectIdsByNamePrefixes(jdbc, namePrefixes));
    }

    /**
     * Purge tp_batch_case rows whose batch belongs to the given projects.
     * tp_batch_case carries neither an FK into tp_exec_batch nor a project
     * column, so the generic FK walk cannot reach it: the batch-domain
     * teardown runs this BEFORE the project sweep, while the batch rows are
     * still present to join against. An empty id list is a no-op. Single
     * source (R40) — the same join purge used to be copied inline per suite.
     */
    public static void batchCaseRowsOfProjects(JdbcTemplate jdbc, List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM tp_batch_case WHERE batch_id IN"
                        + " (SELECT id FROM tp_exec_batch WHERE project_id IN ("
                        + join(projectIds.stream().mapToLong(Long::longValue).toArray()) + "))");
    }

    /**
     * Purge child rows that hang off a project-keyed parent through a plain
     * BIGINT column (no FK, no project column of their own — tp_plan_case via
     * tp_exec_plan, tp_data_factory_run via tp_data_factory): the generic FK
     * walk cannot see them, so the join purge must run BEFORE the project
     * sweep, while the parent rows are still present to join against. An
     * empty id list is a no-op. Single source (R40) — the same join shape was
     * about to be spelled per suite.
     */
    public static void childRowsOfProjects(JdbcTemplate jdbc, String childTable,
            String parentFkColumn, String parentTable, List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return;
        }
        String ids = projectIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM " + childTable + " WHERE " + parentFkColumn
                + " IN (SELECT id FROM " + parentTable + " WHERE project_id IN (" + ids + "))");
    }

    /**
     * Plans-family teardown single source (R40): tp_plan_case keys on
     * tp_exec_plan and tp_batch_case keys on tp_exec_batch, both through a
     * plain BIGINT column with NO FK (live schema scan 2026-11-16), so the
     * generic FK walk in {@link #byProject} cannot see either. The join
     * purges run BEFORE the project sweep, while the plan/batch parents
     * are still present to join against; then the tracked projects and
     * every FK descendant go down. An empty id list is a no-op, so suites
     * may call this from both lifecycle hooks.
     */
    public static void plansTreeDown(JdbcTemplate jdbc, List<String> extraProjectTables,
            List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return;
        }
        childRowsOfProjects(jdbc, "tp_plan_case", "plan_id", "tp_exec_plan", projectIds);
        batchCaseRowsOfProjects(jdbc, projectIds);
        byProjectList(jdbc, extraProjectTables, projectIds);
    }

    /**
     * Plans-family fixture project create (single source per R40): sweeps any
     * same-code leftover of a crashed run through {@link #plansTreeDown} first,
     * then creates through the real service path so the row passes production
     * validation, and tracks the id for the per-hook teardown. No blanket table
     * wipe is ever needed (R37②).
     */
    public static long createPlanFixtureProject(ProjectService projectService, JdbcTemplate jdbc,
            List<String> extraProjectTables, String namePrefix, String code, List<Long> tracker) {
        long stale = projectIdByCode(jdbc, code);
        if (stale > 0) {
            plansTreeDown(jdbc, extraProjectTables, List.of(stale));
        }
        long pid = projectService.create(1L, new ProjectCreateInput(namePrefix + code, code,
                "zh-CN", "P1", null));
        tracker.add(pid);
        return pid;
    }

    /**
     * Plans-family fixture tester create (single source per R40): exact-username
     * equality pre-delete clears a crashed run's leftover (suite username sets
     * are fixed, so the sweep is deterministic — underscore usernames must never
     * go through the LIKE pattern channel), then the real role/member services
     * qualify the tester and the id is tracked for teardown.
     */
    public static long createTrackedTester(JdbcTemplate jdbc, RoleService roleService,
            MemberService memberService, String username, long pid, List<Long> tracker) {
        usersDown(jdbc, jdbc.queryForList(
                "SELECT id FROM tp_user WHERE username = ?", Long.class, username));
        long uid = jdbc.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        long roleId = roleService.roles().stream().filter(r -> r.code().equals("tester"))
                .map(RoleInfo::id).findFirst().orElseThrow();
        roleService.assignUserRoles(uid, List.of(roleId));
        memberService.add(pid, uid, 1);
        tracker.add(uid);
        return uid;
    }

    /**
     * Spec-family (W1c) extra non-FK project tables for plans-domain teardown:
     * tp_api_spec and tp_api_spec_change_analysis carry project_id without an
     * FK into tp_project (live schema scan 2026-11-16). Single source so the
     * two spec-gate suites never drift apart (R40).
     */
    public static final List<String> SPEC_FAMILY_EXTRA_TABLES =
            List.of("tp_api_spec", "tp_api_spec_change_analysis");

    /**
     * Suites-family extra non-FK project tables (live schema scan 2026-11-16):
     * tp_regression_suite carries project_id without a FK into tp_project, so
     * the generic FK walk cannot reach it; tp_executor is deliberately NOT
     * listed — it is the shared fleet registry (no project column) whose rows
     * belong to the running services, never fixture data the blanket TRUNCATE
     * had legitimate claim to. Single source so the two suites-domain suites
     * never drift apart (R40).
     */
    public static final List<String> SUITE_FAMILY_EXTRA_TABLES =
            List.of("tp_regression_suite");

    /**
     * Suites-family teardown entry (R40 single source): tracked testers down,
     * then the plans tree purged with {@link #SUITE_FAMILY_EXTRA_TABLES} bound
     * internally, so the two suites-domain suites never spell (and drift on)
     * the purge sequence themselves. Idempotent: emptied trackers are no-ops,
     * safe from both lifecycle hooks.
     */
    public static void suitesTreeDown(JdbcTemplate jdbc, List<Long> testers,
            List<Long> projects) {
        if (!testers.isEmpty()) {
            // Guarded: a caller passing an immutable empty list (List.of())
            // has nothing to purge and nothing to clear.
            usersDown(jdbc, testers);
            testers.clear();
        }
        plansTreeDown(jdbc, SUITE_FAMILY_EXTRA_TABLES, projects);
        projects.clear();
    }

    /**
     * Suites-family fixture project create (R40 single source): leftover
     * re-sweep + real service create + id tracking with
     * {@link #SUITE_FAMILY_EXTRA_TABLES} bound internally.
     */
    public static long createSuiteFixtureProject(ProjectService projectService,
            JdbcTemplate jdbc, String code, List<Long> tracker) {
        return createPlanFixtureProject(projectService, jdbc, SUITE_FAMILY_EXTRA_TABLES,
                "项目", code, tracker);
    }

    /**
     * Purge tp_comment rows attached to the defects of the given projects.
     * tp_comment is polymorphic (host_type + host_id) and declares no FK into
     * tp_defect, so the generic FK walk cannot reach it: the join purge must
     * run BEFORE the project sweep, while the defect rows are still present
     * to join against. The host_type guard keeps unrelated hosts whose ids
     * collide numerically out of the purge. An empty id list is a no-op.
     * Single source (R40) — the defect-domain suites converted off blanket
     * TRUNCATE share this shape.
     */
    public static void defectCommentRowsOfProjects(JdbcTemplate jdbc, List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM tp_comment WHERE host_type = 'defect' AND host_id IN"
                        + " (SELECT id FROM tp_defect WHERE project_id IN ("
                        + join(projectIds.stream().mapToLong(Long::longValue).toArray()) + "))");
    }

    /**
     * Purge the batch in-site notification rows (BATCH_FAILED/BATCH_FINISHED)
     * of the given projects. tp_notification carries no FK into tp_project;
     * the dedup key is {@code EVENT:{batchId}:{userId}}, which ties the row
     * back to the batch and through it to the project. Run BEFORE the project
     * sweep, while the batch rows are still present to join against. The
     * numeric-segment guard keeps unrelated dedup-key shapes out of the cast.
     * An empty id list is a no-op. Single source per R40.
     */
    public static void batchNotificationsOfProjects(JdbcTemplate jdbc, List<Long> projectIds) {
        if (projectIds.isEmpty()) {
            return;
        }
        jdbc.update("DELETE FROM tp_notification WHERE event_type IN ('BATCH_FAILED','BATCH_FINISHED')"
                        + " AND split_part(dedup_key, ':', 2) ~ '^[0-9]+$'"
                        + " AND split_part(dedup_key, ':', 2)::bigint IN"
                        + " (SELECT id FROM tp_exec_batch WHERE project_id IN ("
                        + join(projectIds.stream().mapToLong(Long::longValue).toArray()) + "))");
    }

    /**
     * Delete tp_training_sample rows that still point at existing test cases.
     * tp_training_sample carries no FK into tp_test_case and the reject-review
     * bypass writes sample rows off-case, so case-domain suites purge them
     * BEFORE the sweep: sample rows have no project_id column the generic FK
     * walk can reach. Single source (R40) for every case-domain teardown —
     * the same targeted delete used to be copied inline per suite.
     */
    public static void purgeTrainingSamples(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM tp_training_sample WHERE case_id IN (SELECT id FROM tp_test_case)");
    }

    /**
     * Claim-window hygiene census (fresh-install core gate triage): ids of
     * still-pending exec tasks whose project sits inside the current test
     * JVM's run window. Projects are auto-increment rows, so a primary key
     * above the suite-start floor can only be a fixture project created by
     * this run — factory-shipped rows and live-service rows of shipped
     * projects can never match (R37). The class-end guard
     * {@code ExecClaimWindowGuard} consumes this instead of spelling its
     * own SQL (R40), and the window-end gate re-reads the same shape.
     */
    public static List<Long> pendingTaskIdsAboveProjectFloor(JdbcTemplate jdbc, long projectFloor) {
        return jdbc.queryForList(
                "SELECT id FROM tp_exec_task WHERE status = 1 AND project_id > ? ORDER BY id",
                Long.class, projectFloor);
    }

    /**
     * Sweep fixture-registered executor rows by name prefix (single source
     * per R40). tp_executor is the shared fleet registry: it has no project
     * column, so {@link #byProject} never reaches it, and claim-family suites
     * register run-scoped executors under fixed per-suite prefixes. Rows that
     * outlive their class keep sitting in the candidate pool
     * {@code ExecTaskServiceImpl.claim} scans inside the same JVM (r20 CI
     * fresh-install triage: representative classes isolate green, full suite
     * red). Deletes stay confined to the listed prefixes — factory-shipped
     * and live-service executor rows can never match (R37).
     */
    public static void executorsByNamePrefixes(JdbcTemplate jdbc, String... prefixes) {
        for (String prefix : prefixes) {
            jdbc.update("DELETE FROM tp_executor WHERE name LIKE ?", prefix + "%");
        }
    }

    /**
     * Seed one capability-bearing fixture executor (single source per R40,
     * the create counterpart of {@link #executorsByNamePrefixes}). A
     * fresh-install database ships tp_executor empty and no executor process
     * heartbeats into it, so the perf engine-routing gate
     * (PerfEngineRoutingSupport.explainNoCapablePerfExecutorForEnvelope)
     * rejects every launch whenever another suite's leftover row is online
     * without an engine face — "online 1, capable 0" (r22 fresh-install
     * triage: the green-on-dev answer was the persistent runner filling that
     * seat, physically absent on CI). The row is written with the same column
     * shape ExecutorServiceImpl.register persists (status online, fresh
     * heartbeat, capabilities JSONB, protocol version inside the compat
     * window), which keeps the gate deterministic without any runner process.
     * The name is prefix + run-unique token: concurrent suites never collide
     * on it, and teardown through the prefix channel only ever removes this
     * fixture family — never factory or live-service rows (R37).
     *
     * @param engines capability-face engines, e.g. "K6", "GATLING"
     * @return the executor row id (RETURNING anchor, no max(id) race)
     */
    public static long seedCapableExecutor(JdbcTemplate jdbc, String namePrefix,
            String... engines) {
        StringBuilder face = new StringBuilder("[");
        for (int i = 0; i < engines.length; i++) {
            face.append(i > 0 ? "," : "").append('"').append(engines[i]).append('"');
        }
        String uniq = Long.toString(System.nanoTime() % 1_000_000_000L);
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_executor (name, end_types, browsers, capacity, running_cnt,"
                        + " status, version, capabilities, last_heartbeat)"
                        + " VALUES (?, 'web,app', 'chromium', 2, 0, 1, '1.0.0',"
                        + " jsonb_build_object('engines', CAST(? AS jsonb)), now()) RETURNING id",
                Long.class, namePrefix + uniq, face.append(']').toString());
        return id;
    }

    /**
     * R37 teardown for suites that seed a handful of rows and can track their
     * primary keys: deletes exactly the tracked ids — no blanket table wipe,
     * no sequence reset (the generation-family postmortem: a per-test
     * {@code TRUNCATE tp_ai_task RESTART IDENTITY} rewound the shared dev
     * sequence and orphaned queued tasks' idempotency markers). An empty
     * list is a no-op, so callers may run this from both lifecycle hooks.
     */
    public static void byIds(JdbcTemplate jdbc, String table, List<Long> ids) {
        for (Long id : ids) {
            jdbc.update("DELETE FROM " + table + " WHERE id = ?", id);
        }
    }

    /**
     * Current max primary key of {@code table} (0 when empty). Pair it with
     * {@link #deleteSince} in suites that cannot capture the ids they create
     * (scheduled jobs, upsert services) — the R37 channel that replaces the
     * blanket DELETE/TRUNCATE teardown.
     */
    public static long maxId(JdbcTemplate jdbc, String table) {
        Long v = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM " + table, Long.class);
        return v == null ? 0L : v;
    }

    /**
     * Delete exactly the rows of {@code table} whose id exceeds a watermark
     * captured before the test ran: rows appearing above the floor during the
     * test belong to this run (the chain database has a single writing chain,
     * R47), so the teardown stays run-owned and never touches shipped rows.
     * An empty window is a no-op.
     */
    public static void deleteSince(JdbcTemplate jdbc, String table, long idFloor) {
        // tp_audit_log is append-only (V18d trigger): route the sweep through
        // the same transaction-local GUC channel deleteTree uses, so the
        // fixture never trips 2F005 while pooled connections keep no open
        // permission window.
        if ("tp_audit_log".equals(table)) {
            sweepAuditWindow(jdbc, idFloor);
            return;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM " + table + " WHERE id > ? ORDER BY id", Long.class, idFloor);
        byIds(jdbc, table, ids);
    }

    /**
     * R37 teardown for suites that create rows in a parent table with an FK
     * child graph (tp_skill -> tp_skill_version / tp_skill_tag / tp_skill_usage /
     * tp_project_skill): deletes exactly the tracked parent ids, children
     * first, walking the live FK metadata — the same recursive algorithm
     * {@link #byProject} uses, keyed on the parent primary key instead of a
     * project id. System-level fixture rows ({@code project_id IS NULL}) are
     * unreachable from a project sweep, which is why skill suites need this
     * second entry point. An empty id list is a no-op.
     */
    public static void byIdTree(JdbcTemplate jdbc, String table, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        deleteTree(jdbc, table, "id",
                join(ids.stream().mapToLong(Long::longValue).toArray()));
    }

    /**
     * Remove the fixture's in-site notification rows by their dedup key
     * prefix: approval notifications carry {@code EVENT:objectId:version:user}
     * so rows triggered by system-level entities ({@code project_id IS NULL})
     * escape the project sweep and would otherwise accumulate across runs.
     * Matching deletes are confined to the listed event types AND the tracked
     * object ids — factory rows and other suites' rows never match.
     */
    public static void approvalNotifications(JdbcTemplate jdbc,
            List<String> eventTypes, List<Long> objectIds) {
        if (eventTypes.isEmpty() || objectIds.isEmpty()) {
            return;
        }
        String inList = eventTypes.stream()
                .map(e -> "'" + e + "'")
                .collect(Collectors.joining(","));
        String alternation = String.join("|", eventTypes);
        String objs = join(objectIds.stream().mapToLong(Long::longValue).toArray());
        jdbc.update("DELETE FROM tp_notification WHERE event_type IN ("
                + inList + ") AND dedup_key ~ ('^(" + alternation + "):(" + objs + ")(:|$)')");
    }

    /** Skill approval events emitted by the create/submit/approve chain. */
    private static final List<String> SKILL_EVENTS = List.of(
            "SKILL_APPROVAL_REQUEST", "SKILL_APPROVED", "SKILL_APPROVAL_REJECTED");

    /**
     * Unique per-run token appended to fixture text bodies so list/market
     * counts on the shared dev database stay scoped to this run (factory
     * templates and crashed-run rows never carry it). Single source per R40.
     */
    public static String newToken() {
        return "FX" + Long.toString(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 30), 36);
    }

    /**
     * Teardown chain for the skill fixture suites (single source per R40):
     * deletes tracked skills with their FK children, their approval
     * notifications and project-keyed rows of the run, then clears both
     * trackers so a second sweep call is a no-op.
     */
    public static void skillFixtureDown(JdbcTemplate jdbc, List<Long> skillIds,
            List<Long> projectIds, List<String> extraProjectTables) {
        byIdTree(jdbc, "tp_skill", skillIds);
        approvalNotifications(jdbc, SKILL_EVENTS, skillIds);
        byProject(jdbc, extraProjectTables,
                projectIds.stream().mapToLong(Long::longValue).toArray());
        projectIds.clear();
        skillIds.clear();
    }

    /** Tool approval events emitted by the create/submit/approve chain. */
    private static final List<String> TOOL_EVENTS = List.of(
            "TOOL_APPROVAL_REQUEST", "TOOL_APPROVED", "TOOL_APPROVAL_REJECTED");

    /**
     * Sweep the tool approval notifications of the given tool ids (public
     * entry onto {@code TOOL_EVENTS} so seed families can keep the shared
     * notification table clean without duplicating the event list).
     */
    public static void toolApprovalNotifications(JdbcTemplate jdbc, List<Long> toolIds) {
        approvalNotifications(jdbc, TOOL_EVENTS, toolIds);
    }

    /**
     * Delete tracked tp_tool rows and everything hung off them: invocation
     * rows (tp_tool_invocation carries a tool_id WITHOUT an FK constraint, so
     * the generic FK walk cannot see them), the tools with their FK children
     * (tp_tool_version, tp_project_tool) and their approval notifications.
     * An empty id list is a no-op. Single source for the tool families (R40).
     */
    public static void deleteTools(JdbcTemplate jdbc, List<Long> toolIds) {
        if (toolIds.isEmpty()) {
            return;
        }
        String ids = join(toolIds.stream().mapToLong(Long::longValue).toArray());
        jdbc.update("DELETE FROM tp_tool_invocation WHERE tool_id IN (" + ids + ")");
        byIdTree(jdbc, "tp_tool", toolIds);
        approvalNotifications(jdbc, TOOL_EVENTS, toolIds);
    }

    /**
     * Teardown chain for the tool fixture suites (single source per R40):
     * deletes tracked tools with their invocations/children/notifications,
     * then the fixture projects with their project-keyed extras. Trackers are
     * cleared, so a second call is a no-op.
     */
    public static void toolFixtureDown(JdbcTemplate jdbc, List<Long> toolIds,
            List<Long> projectIds, List<String> extraProjectTables) {
        deleteTools(jdbc, toolIds);
        byProject(jdbc, extraProjectTables,
                projectIds.stream().mapToLong(Long::longValue).toArray());
        projectIds.clear();
        toolIds.clear();
    }

    /**
     * Delete rows of {@code table} that a tracked fixture user created AND
     * that live inside a tracked fixture project. Owner-only deletes are a
     * factory-row hazard on the shared dev database: a sequence-rewinding
     * fixture can hand a new fixture user an id that factory rows already
     * carry, and then {@code WHERE created_by = ?} silently wipes shipped
     * baselines (the tp_tool 0/12 ledger postmortem). The project conjunct
     * makes the delete structurally safe — factory/system-level rows have
     * {@code project_id IS NULL} or belong to shipped projects, so they can
     * never match a fixture-project set. An empty owner or project list is a
     * no-op.
     */
    public static void ownedByInProjects(JdbcTemplate jdbc, String table,
            List<Long> ownerIds, List<Long> projectIds) {
        if (ownerIds.isEmpty() || projectIds.isEmpty()) {
            return;
        }
        String owners = join(ownerIds.stream().mapToLong(Long::longValue).toArray());
        String projects = join(projectIds.stream().mapToLong(Long::longValue).toArray());
        jdbc.update("DELETE FROM " + table + " WHERE created_by IN (" + owners + ")"
                + " AND project_id IN (" + projects + ")");
    }

    /**
     * R37 teardown for the user-suite family (single source per R40): deletes
     * the tracked fixture users with their FK children (tp_user_role etc.,
     * walked generically), the in-site notifications addressed to them, the
     * LOGIN_LOCKED alerts emitted about them (dedup_key is
     * {@code LOGIN_LOCKED:<lockee>:<day>:<recipient>} — segment 2 names the
     * locked user, so rows sent to shipped factory admins about a fixture
     * lockee are swept too), and the audit rows naming them (tp_audit_log
     * carries object_id WITHOUT an FK into tp_user, invisible to the generic
     * tree walk; byIdTree routes it through the retention-GUC delete). An
     * empty id list is a no-op.
     */
    public static void usersDown(JdbcTemplate jdbc, List<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        String ids = join(userIds.stream().mapToLong(Long::longValue).toArray());
        String textIds = userIds.stream().map(v -> "'" + v + "'")
                .collect(Collectors.joining(","));
        jdbc.update("DELETE FROM tp_notification WHERE user_id IN (" + ids + ")");
        jdbc.update("DELETE FROM tp_notification"
                + " WHERE event_type = 'LOGIN_LOCKED'"
                + " AND split_part(dedup_key, ':', 2) IN (" + textIds + ")");
        byIdTree(jdbc, "tp_audit_log", jdbc.queryForList(
                "SELECT id FROM tp_audit_log WHERE object_type = 'user'"
                        + " AND object_id IN (" + textIds + ")", Long.class));
        byIdTree(jdbc, "tp_user", userIds);
    }

    /**
     * Sweep every user whose username matches a LIKE pattern — the stale
     * drop + teardown channel for suites that keep a fixed per-suite username
     * prefix on the shared dev database, so a crashed run never leaves
     * unique-key collisions for the next run's creates.
     */
    public static void usersByPatternDown(JdbcTemplate jdbc, String usernamePattern) {
        usersDown(jdbc, jdbc.queryForList(
                "SELECT id FROM tp_user WHERE username LIKE ?", Long.class, usernamePattern));
    }

    /**
     * Resolve a fixture project's id by its unique code (0 = absent). Used to
     * re-sweep a crashed run's leftover fixture before recreating it, so no
     * blanket table delete is ever needed.
     */
    /**
     * Seed the perf-suite fixture shape (single source for the four perf suites:
     * project + one env + a 1000-mode whitelist row). INSERT ... RETURNING pins
     * our own ids on the shared dev database -- a SELECT max(id) could anchor
     * onto a concurrent suite's insert. Returns {projectId, envId}.
     */
    public static long[] seedPerfFixtureProject(JdbcTemplate jdbc, String namePrefix,
            String codePrefix, String envName, String baseUrl) {
        return seedPerfFixtureProject(jdbc, namePrefix, codePrefix, envName, 1,
                baseUrl, 1000, null);
    }

    /**
     * Full perf-seed entry (R40 single source, covers every perf-suite variant):
     * project row + env row with an explicit env type, plus an optional
     * tp_perf_whitelist row. {@code maxConcurrency <= 0} skips the whitelist
     * insert (suites that test the no-whitelist denial path seed no row);
     * {@code allowedModes} nulls to the empty array, matching the shape the
     * launch gate reads. Returns {projectId, envId} from RETURNING anchors.
     */
    public static long[] seedPerfFixtureProject(JdbcTemplate jdbc, String namePrefix,
            String codePrefix, String envName, int envType, String baseUrl,
            int maxConcurrency, String allowedModes) {
        String uniq = Long.toString(System.nanoTime() % 1_000_000_000L);
        long projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, namePrefix + uniq, codePrefix + uniq);
        long envId = jdbc.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, projectId, envName, envType, baseUrl);
        if (maxConcurrency > 0) {
            jdbc.update("INSERT INTO tp_perf_whitelist (project_id, env_id, max_concurrency,"
                            + " allowed_modes) VALUES (?, ?, ?, CAST(? AS jsonb))",
                    projectId, envId, maxConcurrency, allowedModes == null ? "[]" : allowedModes);
        }
        return new long[] {projectId, envId};
    }

    public static long projectIdByCode(JdbcTemplate jdbc, String code) {
        List<Long> rows = jdbc.queryForList(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /**
     * Create a fixture project idempotently: sweeps any same-code leftover of
     * a crashed run, then inserts a fresh row tracked in {@code tracker}.
     * Single source per R40 (was copy-pasted across the skill suites).
     */
    /**
     * Seed a nonprivileged test account (role 3) and return its id. Both the
     * skill and tool market suites need the same row; single source per R40.
     */
    public static long seedUser(JdbcTemplate jdbc, String username, String displayName) {
        Long id = jdbc.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, 'x', ?, 3, 1) RETURNING id",
                Long.class, username, displayName);
        return id;
    }

    public static long createTrackedProject(JdbcTemplate jdbc, String code, List<Long> tracker) {
        byProject(jdbc, projectIdByCode(jdbc, code));
        Long id = jdbc.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES (?, ?, 'zh-CN', 1) RETURNING id
                """, Long.class, "项目" + code, code);
        tracker.add(id);
        return id;
    }

    /**
     * Service-path fixture project create tracked for teardown. Unlike
     * {@link #createTrackedProject} (raw SQL, fixed code, sweep-then-insert),
     * this goes through {@code ProjectService.create} so the seeded row
     * passes the same validation as production calls, and qualifies the code
     * with the run token so concurrent suites never collide on the unique key.
     */
    public static long createServiceProject(ProjectService projectService,
            List<Long> tracker, String runToken, String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code,
                code + "_" + runToken, "zh-CN", "P1", null));
        tracker.add(id);
        return id;
    }

    /**
     * Reportcenter teardown pair: delete tp_report rows created after the
     * run's id watermark, then every row owned by the run's tracked projects.
     * byProjectList also sweeps tp_exec_task (project-keyed, default set),
     * which covers the seeded task rows belonging to those projects.
     */
    public static void reportsAndProjects(JdbcTemplate jdbc, long reportIdFloor,
            List<Long> projectIds) {
        deleteSince(jdbc, "tp_report", reportIdFloor);
        byProjectList(jdbc, projectIds);
    }

    /**
     * Delete the fixture projects' rows and every FK descendant, in
     * dependency order. {@code extraProjectTables} names tables that carry a
     * {@code project_id} column but no FK constraint into tp_project.
     */
    public static void byProject(JdbcTemplate jdbc, List<String> extraProjectTables, long... projectIds) {
        long[] kept = java.util.Arrays.stream(projectIds).filter(id -> id > 0).distinct().toArray();
        if (kept.length == 0) {
            return;
        }
        String ids = join(kept);
        // Case-keyed children (tag links, case/script versions) declare no FK, so
        // the generic walk below cannot see them: purge them first, while the
        // run's cases still exist to join against — same posture as
        // batchCaseRowsOfProjects. The blanket TRUNCATE these sweeps replaced
        // cleared them too, so skipping this would silently leak rows.
        String runCaseIds = "SELECT id FROM tp_test_case WHERE project_id IN (" + ids + ")";
        for (String table : CASE_KEYED_TABLES_WITHOUT_FK) {
            jdbc.update("DELETE FROM " + table + " WHERE case_id IN (" + runCaseIds + ")");
        }
        // A script version row may carry only script_id (case column left null by
        // older writes), so reach those through tp_script, which does carry the FK.
        jdbc.update("DELETE FROM tp_script_version WHERE script_id IN"
                + " (SELECT id FROM tp_script WHERE case_id IN (" + runCaseIds + "))");
        List<String> projectTables = new java.util.ArrayList<>(DEFAULT_PROJECT_TABLES);
        projectTables.addAll(extraProjectTables);
        for (String table : projectTables) {
            deleteTree(jdbc, table, "project_id", ids);
        }
        deleteTree(jdbc, "tp_project", "id", ids);
    }

    /**
     * Delete rows of {@code table} whose {@code matchCol} is in {@code ids},
     * after deleting every referencing row in the whole database (children
     * first, recursively). Generic over the FK graph: each child is found by
     * its own FK column name, so no table list is hard-coded here.
     */
    private static void deleteTree(JdbcTemplate jdbc, String table, String matchCol, String ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // query() with an explicit mapper: queryForList(Object[].class) would
        // route through the reflection constructor and fail on 4-column rows.
        List<Object[]> refs = jdbc.query(
                "SELECT c.conrelid::regclass::text, a.attname, c.confdeltype, ra.attname"
                        + " FROM pg_constraint c"
                        + " JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]"
                        + " JOIN pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = c.confkey[1]"
                        + " WHERE c.contype = 'f' AND c.confrelid = ?::regclass"
                        + " AND cardinality(c.conkey) = 1"
                        + " ORDER BY c.conrelid::regclass::text",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                table);
        for (Object[] ref : refs) {
            String child = bareName(ref[0].toString());
            if (child.equals(table)) {
                continue; // self-reference handled by the plain delete below
            }
            String childCol = ref[1].toString();
            boolean cascade = "c".equals(String.valueOf(ref[2]));
            if (cascade) {
                continue; // ON DELETE CASCADE removes the child rows (and its
            }             // own cascade subtree) with the parent delete below
            // A child FK points at the parent's REFERENCED column, which may
            // differ from the column the parent rows were matched by (sweeping
            // tp_document by project_id while tp_api_spec_preview references its
            // id). Matching the child with the parent's match values deletes
            // nothing and the parent delete then dies on the FK (measured r4),
            // so translate through the parent rows first.
            String childVals = ids;
            if (!ref[3].toString().equals(matchCol)) {
                List<String> parentKeys = jdbc.queryForList(
                        "SELECT " + ref[3] + "::text FROM " + table
                                + " WHERE " + matchCol + " IN (" + ids + ")", String.class);
                if (parentKeys.isEmpty()) {
                    continue; // no parent rows here, so the child has nothing to sweep
                }
                childVals = String.join(",", parentKeys);
            }
            // Grandchildren hang off the child rows' own primary key (repo
            // convention: an `id` column), same as the python twin.
            List<String> childRowIds = hasIdColumn(jdbc, child)
                    ? jdbc.queryForList("SELECT id::text FROM " + child
                            + " WHERE " + childCol + " IN (" + childVals + ")", String.class)
                    : List.of();
            if (childRowIds.isEmpty()) {
                jdbc.update("DELETE FROM " + child + " WHERE " + childCol + " IN (" + childVals + ")");
                continue;
            }
            deleteTree(jdbc, child, "id", String.join(",", childRowIds));
            jdbc.update("DELETE FROM " + child + " WHERE " + childCol + " IN (" + childVals + ")");
        }
        if ("tp_audit_log".equals(table)) {
            deleteAuditLogRows(jdbc, matchCol, ids);
        } else {
            jdbc.update("DELETE FROM " + table + " WHERE " + matchCol + " IN (" + ids + ")");
        }
    }

    /**
     * tp_audit_log is append-only: the V18d trigger rejects DELETE unless the
     * transaction-local GUC {@code tp.audit_retention_allowed} is set, the same
     * gate the product retention channel (AuditRetentionCleaner) opens. Fixture
     * teardown legitimately removes only its own projects' audit rows, so it
     * runs the delete inside a self-managed short transaction — SET LOCAL
     * semantics end with the commit, so the pooled connection never leaks an
     * open permission window to its next borrower.
     */
    /**
     * Remove every ledger row above a watermark in ONE serialized statement
     * under a table-level EXCLUSIVE lock — the same lock mode
     * AuditLogServiceImpl.record uses before it reads the chain tail. Without
     * the lock, a concurrent JVM can append a row between the id enumeration
     * and the delete; that survivor stays chained to a row the sweep then
     * removes, leaving a mid-chain gap that permanently fails verifyChain
     * (observed on the shared slot0 ledger, 2026-11-25). Locking inside the
     * sweep transaction makes enumerate-and-delete atomic against record().
     */
    private static void sweepAuditWindow(JdbcTemplate jdbc, long idFloor) {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            throw new IllegalStateException("SharedDbFixtureSweep needs a JdbcTemplate backed by a DataSource to sweep tp_audit_log");
        }
        try (Connection c = ds.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement lock = c.createStatement();
                    PreparedStatement del = c.prepareStatement(
                            "DELETE FROM tp_audit_log WHERE id > ?")) {
                lock.execute("SELECT set_config('tp.audit_retention_allowed', 'on', true)");
                lock.execute("LOCK TABLE tp_audit_log IN EXCLUSIVE MODE");
                del.setLong(1, idFloor);
                del.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fixture audit window sweep failed above id " + idFloor, e);
        }
    }

    private static void deleteAuditLogRows(JdbcTemplate jdbc, String matchCol, String ids) {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            throw new IllegalStateException("SharedDbFixtureSweep needs a JdbcTemplate backed by a DataSource to sweep tp_audit_log");
        }
        try (Connection c = ds.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('tp.audit_retention_allowed', 'on', true)");
                s.executeUpdate("DELETE FROM tp_audit_log WHERE " + matchCol + " IN (" + ids + ")");
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fixture audit-row sweep failed for ids " + ids, e);
        }
    }

    /**
     * Remove stale tp_asset_reference pins whose referrer_id sits in one asset
     * fixture suite's synthetic host range [hostBase, hostBase + 1e9). The
     * asset suites pin against synthetic host row ids (far above any real
     * tp_case_step/tp_test_case range) that restart at the same base on every
     * run, so a crashed run's leftover pins would collide with the next run's
     * uq_asset_reference_host key. Test-side recovery only: real hosts carry
     * ids far below the synthetic bases, and surefire runs the fixture classes
     * sequentially, so no live suite row ever falls inside one suite's range.
     */
    public static void sweepSyntheticPins(JdbcTemplate jdbc, long hostBase) {
        jdbc.update("DELETE FROM tp_asset_reference WHERE referrer_id >= ? AND referrer_id < ?",
                hostBase, hostBase + 1_000_000_000L);
    }

    private static boolean hasIdColumn(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_name = ? AND column_name = 'id'", Integer.class, table);
        return n != null && n > 0;
    }

    /** Strip the schema qualifier and quoting from a regclass rendering. */
    private static String bareName(String regclass) {
        String s = regclass.contains(".") ? regclass.substring(regclass.indexOf('.') + 1) : regclass;
        return s.replace("\"", "");
    }

    private static String join(long[] values) {
        StringBuilder sb = new StringBuilder();
        for (long v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }
}
