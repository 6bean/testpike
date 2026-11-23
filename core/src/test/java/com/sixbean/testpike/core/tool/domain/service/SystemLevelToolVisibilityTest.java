package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingInput;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingItem;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolFcAssemblyItem;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AR batch: system-level assets are visible and assembled by default.
 * <p>Dual-bucket model (system level = project_id IS NULL ∪ project-owned):
 * the project-filtered list view must equal "this project ∪ system level"
 * (never swallow the system bucket when the header project is selected), and
 * the AI function-calling assembly must include published system-level tools
 * with zero binding rows (opt-out = explicit enabled=0 override row).
 * <p>Contract gates (adjudication file section 3, items 1-2):
 * <ul>
 *   <li>visibility: three-bucket fixture (project A binding ∪ system level ∪
 *       project B private) → project A view = own ∪ system, B private never
 *       leaks (H1 isolation untouched);</li>
 *   <li>assembly: no binding rows → published system tools reachable;
 *       enabled=0 override → excluded; draft system tool → zero entries
 *       (published door not loosened); project-private still requires an
 *       enabled binding row (existing semantics unchanged).</li>
 * </ul>
 * <p>Real dev database, no mocks (strict testing standard). Base shape =
 * ToolFcAssemblyServiceTest (same tracked-fixture helpers, FK-walk teardown);
 * assembly assertions compare against the fixture-scoped id view so shipped
 * factory rows and concurrent suites' rows never enter the picture.
 */
@SpringBootTest(classes = CoreApplication.class)
class SystemLevelToolVisibilityTest {

    @Autowired
    private ToolService toolService;

    @Autowired
    private ProjectToolService projectToolService;

    @Autowired
    private ToolFcAssemblyService toolFcAssemblyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ToolSuiteSupport support;

    @BeforeEach
    void openVisibilitySuite() {
        support = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of("tp_user_project"));
    }

    @AfterEach
    void clearVisibilitySuite() {
        support.sweep();
    }

    // ---- helpers ----

    private long createProject(String code) {
        return support.createProject(code);
    }

    /** Published HTTP tool, rows tracked for the fixture-scoped teardown. */
    private long createPublishedHttpTool(Long projectId, String name) {
        return support.createPublishedTool(toolService, 1L, new ToolCreateInput(projectId, name, 2,
                "数据", null, null, null, "{\"type\":\"object\"}", null, 1,
                "{\"url\":\"http://127.0.0.1:9/echo\"}", 30000, null)).id();
    }

    /** Draft-state tool (never submitted) — published-door negative fixture. */
    private long createDraftHttpTool(Long projectId, String name) {
        return support.createTrackedTool(toolService, 1L, new ToolCreateInput(projectId, name, 2,
                "数据", null, null, null, "{\"type\":\"object\"}", null, 1,
                "{\"url\":\"http://127.0.0.1:9/echo\"}", 30000, null)).id();
    }

    private void bind(long projectId, List<ProjectToolBindingItem> items) {
        projectToolService.saveBindings(1L, projectId, new ProjectToolBindingInput(items, null));
    }

    private ProjectToolBindingItem on(long toolId) {
        return new ProjectToolBindingItem(toolId, 1);
    }

    private ProjectToolBindingItem off(long toolId) {
        return new ProjectToolBindingItem(toolId, 0);
    }

    private List<String> namesOf(List<ToolInfo> list) {
        return list.stream().map(ToolInfo::name).toList();
    }

    /**
     * Fixture-scoped view of an assembly result: only this run's tracked tool
     * ids survive the filter, so the assertions are insensitive to the
     * shipped factory rows the old TRUNCATE used to wipe.
     */
    private List<Long> fixtureAssemblyIds(List<ToolFcAssemblyItem> items) {
        return items.stream()
                .map(ToolFcAssemblyItem::toolId)
                .filter(support.tools()::contains)
                .toList();
    }

    // ---- ① list visibility: project view = this project ∪ system level ----

    @Test
    void listWithProjectFilterReturnsOwnUnionSystemLevel_otherProjectPrivateNeverLeaks() {
        long pidA = createProject("ARV01A");
        long pidB = createProject("ARV01B");
        createDraftHttpTool(null, "sys-tool");
        createDraftHttpTool(pidA, "a-private");
        createDraftHttpTool(pidB, "b-private");

        // Project A view = A private ∪ system level (2 rows), B private
        // excluded; the run keyword narrows the page to this run's rows.
        var pageA = toolService.list(new ToolQuery(1, 20, support.token(), null, null, pidA,
                null, null, null));
        assertEquals(2, pageA.total(), "project filter must equal own ∪ system bucket");
        assertTrue(namesOf(pageA.list()).containsAll(
                List.of("sys-tool " + support.token(), "a-private " + support.token())),
                String.join(",", namesOf(pageA.list())));
        assertFalse(namesOf(pageA.list()).contains("b-private " + support.token()),
                "H1 isolation: other project private must not leak into project view");

        // No project filter (admin cross-bucket view) keeps all three rows.
        assertEquals(3, toolService.list(new ToolQuery(1, 20, support.token(), null, null, null,
                null, null, null)).total());
    }

    @Test
    void listKeywordFilterStillCoversSystemLevelRowsUnderProjectSlice() {
        long pidA = createProject("ARV02");
        createDraftHttpTool(null, "sys-searchable");
        createDraftHttpTool(pidA, "a-searchable");
        createDraftHttpTool(pidA, "a-other");

        var hit = toolService.list(new ToolQuery(1, 20, "searchable", null, null, pidA,
                null, null, null));
        assertEquals(2, hit.total(), "keyword filter must not re-narrow the system bucket");
    }

    // ---- ② assembly: published system level reaches every project with zero bindings ----

    @Test
    void assemblyIncludesPublishedSystemToolWithNoBindingRow() {
        long pidA = createProject("ARA01");
        long sysTool = createPublishedHttpTool(null, "sys-published");

        List<ToolFcAssemblyItem> items = toolFcAssemblyService.getAssemblyItems(pidA);
        assertEquals(List.of(sysTool), fixtureAssemblyIds(items),
                "no binding rows: published system tool must be reachable");
    }

    @Test
    void assemblyEnabledZeroOverrideRowOptsOutThatSystemTool() {
        long pidA = createProject("ARA02");
        long pidB = createProject("ARA02B");
        long sysTool = createPublishedHttpTool(null, "sys-opt-out");
        bind(pidA, List.of(off(sysTool)));

        assertTrue(fixtureAssemblyIds(toolFcAssemblyService.getAssemblyItems(pidA)).isEmpty(),
                "explicit enabled=0 override row removes the system tool for project A");
        assertEquals(List.of(sysTool),
                fixtureAssemblyIds(toolFcAssemblyService.getAssemblyItems(pidB)),
                "opt-out is a per-project account: project B default pass-through untouched");

        bind(pidA, List.of(on(sysTool)));
        assertEquals(List.of(sysTool),
                fixtureAssemblyIds(toolFcAssemblyService.getAssemblyItems(pidA)),
                "re-enabling with enabled=1 binding row restores the tool");
    }

    @Test
    void assemblyDraftSystemToolEntersNothing_publishedDoorUnloosened() {
        long pidA = createProject("ARA03");
        createDraftHttpTool(null, "sys-draft");
        assertTrue(fixtureAssemblyIds(toolFcAssemblyService.getAssemblyItems(pidA)).isEmpty(),
                "published gate untouched: draft system tool must never assemble");
    }

    @Test
    void assemblyProjectPrivateStillNeedsEnabledBinding_otherProjectPrivateNeverLeaks() {
        long pidA = createProject("ARA04");
        long pidB = createProject("ARA04B");
        long aPrivate = createPublishedHttpTool(pidA, "a-private");
        long bPrivate = createPublishedHttpTool(pidB, "b-private");

        // Unbound project-private stays out of assembly (existing semantics unchanged)
        assertTrue(fixtureAssemblyIds(toolFcAssemblyService.getAssemblyItems(pidA)).isEmpty(),
                "project-private tool without binding row must not assemble");
        bind(pidA, List.of(on(aPrivate)));
        List<ToolFcAssemblyItem> items = toolFcAssemblyService.getAssemblyItems(pidA);
        assertEquals(List.of(aPrivate), fixtureAssemblyIds(items),
                "own binding enabled=1 still works");
        assertFalse(fixtureAssemblyIds(items).contains(bPrivate),
                "H1: other project private must never enter assembly");
    }

    @Test
    void assemblyOrderStaysToolIdAscendingAcrossBothBuckets() {
        long pidA = createProject("ARA05");
        long sys1 = createPublishedHttpTool(null, "sys-1");
        long own = createPublishedHttpTool(pidA, "a-own");
        long sys2 = createPublishedHttpTool(null, "sys-2");
        // project-private enters only via an enabled binding row (ARA04 semantics);
        // the system bucket enters by pass-through — order spans the union
        bind(pidA, List.of(on(own)));

        List<Long> ids = fixtureAssemblyIds(toolFcAssemblyService.getAssemblyItems(pidA));
        assertEquals(List.of(sys1, own, sys2), ids,
                "deterministic assembly order (tool_id ASC) spans the union, not per-bucket");
    }
}
