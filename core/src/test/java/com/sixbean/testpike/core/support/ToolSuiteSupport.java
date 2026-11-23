package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Per-suite fixture bookkeeping for the tool test families: tracked fixture
 * projects and tools, the pre-test tp_tool id snapshot (so seed families can
 * tell their own creations apart from the shipped baseline), a unique per-run
 * scoping token and the teardown chain. The suites compose one instance
 * instead of copy-pasting the same scaffolding (single source per R40;
 * teardown semantics live in {@link SharedDbFixtureSweep}).
 *
 * <p>Replaces the blanket {@code TRUNCATE tp_tool ... CASCADE} the tool suites
 * used to run before/after each test: that wiped the 48 shipped factory rows
 * (tp_tool is a baseline-floored table, R37) and deadlocked concurrent
 * suites on the shared dev database. Fixture rows are now tracked by id and
 * deleted child-first through the live FK graph.
 */
public final class ToolSuiteSupport {

    private final JdbcTemplate jdbc;
    private final List<String> extraProjectTables;

    /** Fixture projects created by the running suite instance. */
    private final List<Long> projects = new ArrayList<>();

    /** tp_tool rows created by the running suite instance. */
    private final List<Long> tools = new ArrayList<>();

    /** tp_tool id set captured at {@link #begin()} (seed-family diff anchor). */
    private Set<Long> toolIdsAtBegin = new HashSet<>();

    /** Unique per-run token appended to fixture names for count scoping. */
    private final String token = SharedDbFixtureSweep.newToken();

    /** Rows squatting a seed family's contract name, tracked for teardown. */
    private final List<Long> seedSquats = new ArrayList<>();

    public ToolSuiteSupport(JdbcTemplate jdbc, List<String> extraProjectTables) {
        this.jdbc = jdbc;
        this.extraProjectTables = extraProjectTables;
    }

    public String token() {
        return token;
    }

    /** Tracked fixture projects (mutable view for suite-side bookkeeping). */
    public List<Long> projects() {
        return projects;
    }

    /** Tracked tp_tool ids created by the suite. */
    public List<Long> tools() {
        return tools;
    }

    /** Live tp_tool row count on the shared dev database. */
    public int countTools() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tp_tool", Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Sweep leftovers of a crashed run, then snapshot the tp_tool id set.
     * Seed families (initializer-created rows) diff later snapshots against
     * {@link #newToolIds()} instead of tracking call sites.
     */
    public void begin() {
        sweep();
        toolIdsAtBegin = currentToolIds();
    }

    /** tp_tool ids present now but not at {@link #begin()} (this run's rows). */
    public Set<Long> newToolIds() {
        Set<Long> now = currentToolIds();
        now.removeAll(toolIdsAtBegin);
        return now;
    }

    /**
     * Remove every tool row created since {@link #begin()}: invocations
     * (no FK into tp_tool, swept by tool_id), the tools with their FK
     * children and their approval notifications. Used by seed families whose
     * creations are not routed through tracked call sites. The shipped
     * baseline rows (present at begin) are never matched here.
     */
    public void sweepNewTools() {
        SharedDbFixtureSweep.deleteTools(jdbc,
                newToolIds().stream().collect(Collectors.toCollection(ArrayList::new)));
    }

    /**
     * Remove every row this suite instance created: tracked tools with their
     * invocations, FK children and approval notifications, then the fixture
     * projects with their project-keyed extras. Trackers are cleared, so a
     * second call is a no-op.
     */
    public void sweep() {
        SharedDbFixtureSweep.toolFixtureDown(jdbc, tools, projects, extraProjectTables);
    }

    /**
     * Create a tool through the service, track the row and append the run
     * token to the name so name-keyed counts on the shared dev database stay
     * scoped to this run.
     */
    public ToolInfo createTrackedTool(ToolService toolService, long operatorId,
            ToolCreateInput input) {
        ToolCreateInput scoped = withTokenName(input);
        ToolInfo created = toolService.create(operatorId, scoped);
        tools.add(created.id());
        return created;
    }

    /**
     * Create a tool through the service and walk it to published
     * (create→submit→approve), tracking the row for teardown. The operator
     * pair matches the shipped local-mode test accounts.
     */
    public ToolInfo createPublishedTool(ToolService toolService, long operatorId,
            ToolCreateInput input) {
        ToolInfo created = createTrackedTool(toolService, operatorId, input);
        ToolInfo submitted = toolService.submit(operatorId, created.id(), created.version());
        return toolService.approve(2L, submitted.id(), submitted.version());
    }

    /**
     * Construct and immediately snapshot a suite (sweep leftovers of a
     * crashed run + capture the tp_tool id set) — the one-line fixture
     * opening shared by every tool-domain test class, single-sourced here.
     */
    public static ToolSuiteSupport beginSuite(JdbcTemplate jdbc, List<String> extraProjectTables) {
        ToolSuiteSupport support = new ToolSuiteSupport(jdbc, extraProjectTables);
        support.begin();
        return support;
    }

    /** Squatting rows inserted by this run, tracked for teardown. */
    public List<Long> seedSquats() {
        return seedSquats;
    }

    /**
     * R37 teardown for seed-family suites: remove only what this run added
     * (the squatted draft rows), then let the product seeder re-create the
     * temporarily-removed intent rows through the real approval chain, so
     * tp_tool keeps its factory shape and nothing is ever truncated.
     */
    public void restoreSeedFamily(Runnable seeder) {
        SharedDbFixtureSweep.deleteTools(jdbc, seedSquats);
        seedSquats.clear();
        seeder.run();
    }

    /** Ids of the tp_tool rows carrying the given exec_spec intents. */
    public List<Long> idsWithIntent(String... intents) {
        String placeholders = Arrays.stream(intents)
                .map(i -> "?").collect(Collectors.joining(","));
        return jdbc.queryForList(
                "SELECT id FROM tp_tool WHERE exec_spec ->> 'intent' IN (" + placeholders + ")",
                Long.class, (Object[]) intents);
    }

    /** Tool audit rows written for this run's tracked tools under one action. */
    public int toolAuditCount(String action) {
        return toolAuditCount(action, tools());
    }

    /**
     * Tool audit rows under one action scoped to the given tool ids. The
     * audit object_id column stores the tool id as text, so the id list is
     * inlined as quoted text values — shipped rows and concurrent batches'
     * audit rows can never inflate the count on the shared dev database.
     */
    public int toolAuditCount(String action, List<Long> toolIds) {
        if (toolIds.isEmpty()) {
            return 0;
        }
        String quoted = toolIds.stream()
                .map(id -> "'" + id + "'").collect(Collectors.joining(","));
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'tool' AND action = ?"
                        + " AND object_id IN (" + quoted + ")",
                Integer.class, action);
        return count == null ? 0 : count;
    }

    /** Baseline DOC-type create payload (system-level, HTTP-less shape). */
    public static ToolCreateInput docInput(String name, String body) {
        return new ToolCreateInput(null, name, 1, "取数", "描述", body,
                null, null, null, null, null, null, null);
    }

    /**
     * Create a DOC tool through the service and walk it to approved
     * (create→submit→approve), tracking the row for teardown.
     */
    public long createApprovedDocTool(ToolService toolService, long operatorId,
            String name, String body) {
        ToolInfo created = toolService.create(operatorId, docInput(name, body));
        tools.add(created.id());
        toolService.submit(operatorId, created.id(), created.version());
        ToolInfo approved = toolService.approve(operatorId, created.id(), created.version() + 1);
        return approved.id();
    }

    private ToolCreateInput withTokenName(ToolCreateInput input) {
        return new ToolCreateInput(input.projectId(), input.name() + " " + token,
                input.type(), input.category(), input.description(), input.body(),
                input.tagsJson(), input.inputSchemaJson(), input.outputSchemaJson(),
                input.execKind(), input.execSpecJson(), input.timeoutMs(),
                input.submitNow());
    }

    /** Idempotent fixture-project create tracked for teardown. */
    public long createProject(String code) {
        return SharedDbFixtureSweep.createTrackedProject(jdbc, code, projects);
    }

    private Set<Long> currentToolIds() {
        return new HashSet<>(jdbc.queryForList("SELECT id FROM tp_tool", Long.class));
    }
}
