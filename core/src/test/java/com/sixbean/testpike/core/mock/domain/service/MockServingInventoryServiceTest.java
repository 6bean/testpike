package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockServingEndpointPageView;
import com.sixbean.testpike.core.mock.api.dto.MockServingEndpointView;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project-wide serving inventory (V240) — the flat cross-space endpoint list
 * the per-space doors structurally cannot answer. Real dev database, no mocks.
 * <p>Verified: (1) rows aggregate across every space of the project carrying
 * the routing handle per row (endpoint identity = space + method + path);
 * (2) routing-first then evaluation order; (3) 7-day hit counts are truthful
 * per rule (hit rows attributed; a never-hit rule honestly shows 0 instead of
 * vanishing; misses do not count); (4) H1 isolation — another project's
 * spaces and rules never leak into the window; (5) server-side status /
 * method / keyword filters and the shared PageWindow clamp (pageSize capped,
 * nonsense page clamped, truthful totals).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockServingInventoryServiceTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private MockServingInventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "inv" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace(long projectId, String prefix, int priority) {
        String key = prefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(projectId, new MockSpaceInput(
                "Space " + key, key, null, null, "prefix", "none", null, null, priority, null));
    }

    private long newStub(long spaceId, String name, String method, String path) {
        return newStub(spaceId, name, method, path, true);
    }

    private long newStub(long spaceId, String name, String method, String path, boolean enabled) {
        return stubService.create(spaceId, new MockStubInput(name, 100, method, "exact", path,
                "[]", "{\"status\":200}", null, null, null, "manual", null, enabled));
    }

    @Test
    void aggregatesAcrossSpacesWithRoutingHandlePerRow() {
        long projectId = newProject();
        long spaceA = newSpace(projectId, "iva", 10);
        long spaceB = newSpace(projectId, "ivb", 5); // lower priority routes first
        long stubB = newStub(spaceB, "beta health", "GET", "/health");
        long stubA2 = newStub(spaceA, "orders off", "GET", "/orders", false);
        newStub(spaceA, "users get", "GET", "/users");
        // Same route again, but under a second space shadowed behind A's order:
        // the dedup gate scopes uniqueness to one space, so the twin lives in
        // its own space (priority 20 = routes after spaceA) and the inventory
        // must still list it as a distinct endpoint handle.
        long spaceC = newSpace(projectId, "ivc", 20);
        newStub(spaceC, "users get", "GET", "/users");

        MockServingEndpointPageView page =
                inventoryService.listProjectEndpoints(projectId, 1, 50, null, null, null);
        assertEquals(4L, page.total(), "every rule of every space of the project");
        List<String> keys = page.list().stream()
                .map(r -> r.spaceKey() + "|" + r.method() + "|" + r.pathPattern())
                .toList();
        String keyB = spaceKeyOf(spaceB);
        String keyA = spaceKeyOf(spaceA);
        assertEquals(keyB + "|GET|/health", keys.get(0), "space routing order first");
        assertTrue(keys.indexOf(keyA + "|POST|/orders") < keys.indexOf(keyA + "|GET|/orders"),
                "then within-space evaluation order");
        // identity rides per row: same path under a different handle is a different endpoint
        assertTrue(keys.contains(keyB + "|GET|/health") && keys.contains(keyA + "|GET|/users"));
        // the disabled rule is listed (auditors need it) and flagged as such
        var offRow = page.list().stream().filter(r -> r.stubId().equals(stubA2)).findFirst().orElseThrow();
        assertFalse(offRow.enabled(), "enabled switch travels on the row");
    }

    @Test
    void hitCountsAreTruthfulOverTheWindow() {
        long projectId = newProject();
        long spaceId = newSpace(projectId, "ivh", 10);
        long hitStub = newStub(spaceId, "counted", "GET", "/counted");
        long coldStub = newStub(spaceId, "never called", "GET", "/cold");
        long missStub = newStub(spaceId, "only misses", "GET", "/misses");
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, TRUE, now())", spaceId, hitStub);
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, TRUE, now())", spaceId, hitStub);
        // an old hit outside the 7-day window must NOT count
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, TRUE, now() - interval '8 days')", spaceId, hitStub);
        // a miss row attributed to the third rule
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, FALSE, now())", spaceId, missStub);

        var rows = inventoryService.listProjectEndpoints(projectId, 1, 50, null, null, null).list();
        assertEquals(2L, rowOf(rows, hitStub).hits7d(), "two recent matched serves, old ones excluded");
        assertEquals(0L, rowOf(rows, coldStub).hits7d(), "never-hit rules show an honest 0, not missing");
        assertEquals(0L, rowOf(rows, missStub).hits7d(), "misses never count as serves");
    }

    @Test
    void projectIsolationAndServerSideFilters() {
        long mine = newProject();
        long other = newProject();
        long spaceMine = newSpace(mine, "ivo", 10);
        long spaceOther = newSpace(other, "ivp", 10);
        newStub(spaceMine, "mine a", "GET", "/shared-path");
        newStub(spaceOther, "theirs", "GET", "/shared-path");
        long pubStub = newStub(spaceMine, "mine b", "DELETE", "/shared-path");

        var all = inventoryService.listProjectEndpoints(mine, 1, 50, null, null, null);
        assertEquals(2L, all.total(), "the other project's rule never leaks");
        assertTrue(all.list().stream().noneMatch(r -> r.spaceKey().equals(spaceKeyOf(spaceOther))));

        var publishedOnly = inventoryService.listProjectEndpoints(mine, 1, 50, 1, null, null);
        assertEquals(0L, publishedOnly.total(), "draft spaces stay out when asked for published only");
        spaceService.transition(spaceMine, 1, 0);
        publishedOnly = inventoryService.listProjectEndpoints(mine, 1, 50, 1, null, null);
        assertEquals(2L, publishedOnly.total(), "after publishing both rules are live inventory");

        var deleteOnly = inventoryService.listProjectEndpoints(mine, 1, 50, null, "DELETE", null);
        assertEquals(1L, deleteOnly.total());
        assertEquals(pubStub, deleteOnly.list().get(0).stubId().longValue());

        var kw = inventoryService.listProjectEndpoints(mine, 1, 50, null, null, "mine b");
        assertEquals(1L, kw.total(), "keyword filter runs server-side over the full inventory");

        // clamp posture shared with every windowed door: oversized pageSize returns the cap
        var clamped = inventoryService.listProjectEndpoints(mine, 0, 9999, null, null, null);
        assertEquals(1, clamped.page());
        assertEquals(500, clamped.pageSize());
        assertEquals(2L, clamped.total());
    }

    private String spaceKeyOf(long spaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT space_key FROM tp_mock_space WHERE id = ?", String.class, spaceId);
    }

    // ── Server-side sorting through a physical-column whitelist ──

    @Test
    void listSortsByWhitelistedColumnsInBothDirections() {
        long projectId = newProject();
        long spaceZ = newSpace(projectId, "zsrt", 10);
        long spaceA = newSpace(projectId, "asrt", 20); // routes after Z but names first
        long stubZ1 = newStub(spaceZ, "zeta rule", "GET", "/zpath");
        long stubA1 = newStub(spaceA, "alpha rule", "POST", "/apath");
        long stubA2 = newStub(spaceA, "mid rule", "PUT", "/mpath");
        // hits give the derived alias a third distinct order: 2 / 1 / 0
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, TRUE, now())", spaceZ, stubZ1);
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, TRUE, now())", spaceZ, stubZ1);
        jdbcTemplate.update("INSERT INTO tp_mock_hit_log (space_id, stub_id, matched, hit_at)"
                + " VALUES (?, ?, TRUE, now())", spaceA, stubA2);

        var pathAsc = inventoryService.listProjectEndpoints(
                projectId, 1, 50, null, null, null, "path_pattern", "asc");
        assertEquals(List.of("/apath", "/mpath", "/zpath"),
                pathAsc.list().stream().map(MockServingEndpointView::pathPattern).toList(),
                "path_pattern asc must reorder server-side");

        var pathDesc = inventoryService.listProjectEndpoints(
                projectId, 1, 50, null, null, null, "path_pattern", "desc");
        assertEquals(List.of("/zpath", "/mpath", "/apath"),
                pathDesc.list().stream().map(MockServingEndpointView::pathPattern).toList(),
                "path_pattern desc mirrors asc");

        var spaceAsc = inventoryService.listProjectEndpoints(
                projectId, 1, 50, null, null, null, "space_name", "asc");
        assertEquals(List.of("Space asrt-" , "Space asrt-", "Space zsrt-"),
                spaceAsc.list().stream().map(r -> r.spaceName().substring(0, 11)).toList(),
                "space_name asc reorders across spaces");

        var hitsDesc = inventoryService.listProjectEndpoints(
                projectId, 1, 50, null, null, null, "hits_7d", "desc");
        assertEquals(List.of(2L, 1L, 0L),
                hitsDesc.list().stream().map(MockServingEndpointView::hits7d).toList(),
                "hits_7d alias sorts by the projected aggregate value");

        // Default order (no sort requested) keeps the historical routing-first window.
        var dflt = inventoryService.listProjectEndpoints(
                projectId, 1, 50, null, null, null, null, null);
        assertEquals(List.of(stubZ1, stubA1, stubA2),
                dflt.list().stream().map(r -> r.stubId().longValue()).toList(),
                "default stays space-priority routing order");

        // Filters still stack with sorting (same where, different order).
        var pubAsc = inventoryService.listProjectEndpoints(
                projectId, 1, 50, null, "POST", null, "path_pattern", "asc");
        assertEquals(1L, pubAsc.total(), "verb filter still narrows before ordering");
        assertEquals(stubA1, pubAsc.list().get(0).stubId().longValue());
    }

    @Test
    void listSortByInjectionShapedKeyRejected400() {
        long projectId = newProject();
        newStub(newSpace(projectId, "isor", 10), "control", "GET", "/control");
        ServiceException ex = assertThrows(ServiceException.class, () -> inventoryService
                .listProjectEndpoints(projectId, 1, 50, null, null, null, "id OR 1=1", "asc"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("不在白名单"), ex.getMessage());
    }

    private com.sixbean.testpike.core.mock.api.dto.MockServingEndpointView rowOf(
            List<com.sixbean.testpike.core.mock.api.dto.MockServingEndpointView> rows, long stubId) {
        return rows.stream().filter(r -> r.stubId() == stubId).findFirst().orElseThrow();
    }
}
