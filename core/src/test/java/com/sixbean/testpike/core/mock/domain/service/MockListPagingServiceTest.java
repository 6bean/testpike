package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paged management-plane windows over mock stub and scenario lists (
 * contract-layer root-cause clearance of the display-scale iron
 * law: every admin list endpoint must carry page/pageSize; ruling file under
 * docs/change-eval dated 2026-10-14).
 * <p>Verified behaviour against the real dev database (SpringBootTest, zero
 * mocks — same posture as MockScenarioServiceTest):
 * (1) scenario paging keeps core's name order inside each window, and total
 * counts every row of the space so pager arithmetic stays truthful;
 * (2) stub paging keeps evaluation order (priority, id) inside each window;
 * (3) nonsense windows are clamped instead of refused: page &lt; 1 lands on
 * page 1, pageSize &lt; 1 lands on 1, oversized pageSize clamps to the shared
 * cap (same posture as the hit-ledger window);
 * (4) an unknown space is refused with a plain word instead of answering a
 * pretend-empty page (the ghost-id posture of MockHitQueryServiceImpl).
 */
@SpringBootTest(classes = CoreApplication.class)
class MockListPagingServiceTest {

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockScenarioService scenarioService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t397p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    private long newSpace() {
        String key = "pg-" + UUID.randomUUID().toString().substring(0, 10);
        return spaceService.create(newProject(), new MockSpaceInput(
                "Paging Space " + key, key, null, null, "prefix", "none", null, null, 100, null));
    }

    /** Declaration the shared kernel accepts — states only, initial defaults to first. */
    private MockScenarioInput scenarioInput(String name) {
        return new MockScenarioInput(name, "{\"states\":[\"Started\",\"DONE\"]}");
    }

    private MockStubInput plainStub(String name, int priority) {
        return new MockStubInput(name, priority, "GET", "exact", "/paging/" + name + "-" + priority,
                "[]", "{}", null, null, null, "manual", null, true);
    }

    @Test
    void scenarioPagedListKeepsNameOrderAndTruthfulTotal() {
        long spaceId = newSpace();
        // Filed out of name order — core owns the sort (docs editor picklists
        // read positions off it), the window must preserve it page by page.
        scenarioService.create(spaceId, scenarioInput("Charlie"));
        scenarioService.create(spaceId, scenarioInput("Alpha"));
        scenarioService.create(spaceId, scenarioInput("Delta"));
        scenarioService.create(spaceId, scenarioInput("Bravo"));

        var first = scenarioService.listBySpacePaged(spaceId, 1, 2);
        assertEquals(4L, first.total(), "total counts every scenario of the space across pages");
        assertEquals(2, first.list().size());
        assertEquals("Alpha", first.list().get(0).name());
        assertEquals("Bravo", first.list().get(1).name());
        assertEquals(1, first.page());
        assertEquals(2, first.pageSize());

        var second = scenarioService.listBySpacePaged(spaceId, 2, 2);
        assertEquals("Charlie", second.list().get(0).name());
        assertEquals("Delta", second.list().get(1).name());

        // A window past the tail is empty but the total stays truthful.
        var third = scenarioService.listBySpacePaged(spaceId, 3, 2);
        assertEquals(0, third.list().size());
        assertEquals(4L, third.total());
    }

    @Test
    void stubPagedListKeepsEvaluationOrderAndTruthfulTotal() {
        long spaceId = newSpace();
        stubService.create(spaceId, plainStub("late", 300));
        stubService.create(spaceId, plainStub("first", 100));
        stubService.create(spaceId, plainStub("middle", 200));

        var window = stubService.listBySpacePaged(spaceId, 1, 2);
        assertEquals(3L, window.total());
        // Evaluation order (priority asc) is the snapshot's order — the window
        // must not reorder or the page would show a different machine than serves.
        assertEquals("first", window.list().get(0).name());
        assertEquals("middle", window.list().get(1).name());

        var tail = stubService.listBySpacePaged(spaceId, 2, 2);
        assertEquals(1, tail.list().size());
        assertEquals("late", tail.list().get(0).name());
    }

    /**
 * page 3: the scenario window answers header sorts from one
 * core-owned whitelist — two physical columns (name, created/updated stay
 * available), one scope facet and two counted jsonb facets, so "biggest
 * machine first" is a SQL truth over the declaration, not a client-side
 * shuffle of whatever page happens to be loaded. Clearing the sort keeps
 * the historical name order untouched.
 */
    @Test
    void scenarioPagedWindowSortsByWhitelistedColumnsInBothDirections() {
        long spaceId = newSpace();
        // Filed so every whitelisted column answers in a different order than
        // name — a client-side leftover would fail these assertions loudly.
        scenarioService.create(spaceId, new MockScenarioInput("zeta-late",
                "{\"states\":[\"A\",\"B\",\"C\"],\"scope\":\"global\","
                        + "\"transitions\":[{\"from\":\"A\",\"to\":\"B\"},"
                        + "{\"from\":\"B\",\"to\":\"C\"},{\"from\":\"C\",\"to\":\"A\"}]}"));
        scenarioService.create(spaceId, new MockScenarioInput("alpha-early",
                "{\"states\":[\"A\",\"B\"],\"scope\":\"role\","
                        + "\"transitions\":[{\"from\":\"A\",\"to\":\"B\"}]}"));
        scenarioService.create(spaceId, new MockScenarioInput("mid",
                "{\"states\":[\"A\",\"B\",\"C\",\"D\"],\"scope\":\"session\"}"));

        var nameDesc = scenarioService.listBySpacePaged(spaceId, 1, 3, "name", "desc");
        assertEquals("zeta-late", nameDesc.list().get(0).name(), "name desc leads with zeta");
        assertEquals("alpha-early", nameDesc.list().get(2).name());

        var scopeAsc = scenarioService.listBySpacePaged(spaceId, 1, 3, "scope", "asc");
        assertEquals("zeta-late", scopeAsc.list().get(0).name(), "global sorts first");
        assertEquals("alpha-early", scopeAsc.list().get(1).name(), "role next");
        assertEquals("mid", scopeAsc.list().get(2).name(), "session last");

        var statesDesc = scenarioService.listBySpacePaged(spaceId, 1, 3, "states_count", "desc");
        assertEquals("mid", statesDesc.list().get(0).name(), "4 states is the biggest machine");
        assertEquals("zeta-late", statesDesc.list().get(1).name(), "then 3");
        assertEquals("alpha-early", statesDesc.list().get(2).name(), "then 2");

        var transitionsDesc = scenarioService.listBySpacePaged(spaceId, 1, 3, "transitions_count", "desc");
        assertEquals("zeta-late", transitionsDesc.list().get(0).name(), "3 edges first");
        assertEquals("alpha-early", transitionsDesc.list().get(1).name(), "1 edge next");
        assertEquals("mid", transitionsDesc.list().get(2).name(), "0 edges last");

        var transitionsAsc = scenarioService.listBySpacePaged(spaceId, 1, 3, "transitions_count", "asc");
        assertEquals("mid", transitionsAsc.list().get(0).name(), "asc mirrors desc here");

        // Sorting still pages: states desc = mid(4), zeta-late(3), alpha-early(2),
        // so window 2 of size 2 carries the tail of the sorted set.
        var secondWindow = scenarioService.listBySpacePaged(spaceId, 2, 2, "states_count", "desc");
        assertEquals(3L, secondWindow.total(), "total stays truthful under sorting");
        assertEquals("alpha-early", secondWindow.list().get(0).name());

        // No sort requested = the historical name order, untouched.
        var unsorted = scenarioService.listBySpacePaged(spaceId, 1, 3, null, null);
        assertEquals("alpha-early", unsorted.list().get(0).name(), "default stays name ASC");
        assertEquals("mid", unsorted.list().get(1).name());
        assertEquals("zeta-late", unsorted.list().get(2).name());
    }

    /** Injection-shaped keys never reach SQL: fail-closed refusal naming the
     * allowed keys, and the ledger of the space stays intact after the refusal. */
    @Test
    void scenarioSortByInjectionShapedKeyRejectedWithPlainWords() {
        long spaceId = newSpace();
        scenarioService.create(spaceId, scenarioInput("Solo-machine"));

        ServiceException refusal = assertThrows(ServiceException.class,
                () -> scenarioService.listBySpacePaged(spaceId, 1, 20, "name; DROP TABLE x", "asc"));
        assertTrue(refusal.getMessage().contains("白名单"), "plain-word refusal naming the gate");
        assertTrue(refusal.getMessage().contains("states_count"), "the allowed keys are listed");

        // The refusal happens before any query — the window still reads fine.
        assertEquals(1L, scenarioService.listBySpacePaged(spaceId, 1, 20).total(),
                "a rejected sort key changes nothing in the ledger");
    }

    @Test
    void nonsenseWindowsAreClampedAndEchoedBack() {
        long spaceId = newSpace();
        scenarioService.create(spaceId, scenarioInput("Solo-machine"));

        var low = scenarioService.listBySpacePaged(spaceId, 0, 0);
        assertEquals(1, low.page(), "page below 1 lands on page 1");
        assertEquals(1, low.pageSize(), "pageSize below 1 lands on 1");
        assertEquals(1, low.list().size());

        var wide = scenarioService.listBySpacePaged(spaceId, 1, 999999);
        assertTrue(wide.pageSize() <= 500,
                "oversized pageSize clamps to the shared cap, got " + wide.pageSize());
        assertEquals(1L, wide.total());
    }

    @Test
    void pagedWindowsOfUnknownSpaceAreRefusedWithPlainWords() {
        long ghostSpace = 900000001L;
        ServiceException scenarioRefusal = assertThrows(ServiceException.class,
                () -> scenarioService.listBySpacePaged(ghostSpace, 1, 20));
        assertTrue(scenarioRefusal.getMessage().contains("不存在")
                        || scenarioRefusal.getMessage().toLowerCase().contains("not"),
                "the refusal names the missing space instead of faking an empty page: "
                        + scenarioRefusal.getMessage());
        assertThrows(ServiceException.class,
                () -> stubService.listBySpacePaged(ghostSpace, 1, 20));
    }
}
