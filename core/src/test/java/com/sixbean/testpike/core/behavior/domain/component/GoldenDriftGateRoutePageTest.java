package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPair;
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

/**
 * Route-axis ledger paging read : the admin bridge
 * shows every route-replay verdict newest-first with a bounded window, so the
 * page never counts only the rows on it. Filters (domain / control / candidate
 * ref) are all optional and the read is pinned to kind='route' — template and
 * head rows must never leak into this ledger. Runs on the real slot DB (strict
 * testing: no mocks); rows tagged ut-drift-route* are removed per case (R37:
 * no TRUNCATE of factory tables).
 */
@SpringBootTest(classes = CoreApplication.class)
class GoldenDriftGateRoutePageTest {

    private static final String DOMAIN_A = "ut-drift-route-a";
    private static final String DOMAIN_B = "ut-drift-route-b";
    private static final String PACK = "ut-drift-route-pack";

    @Autowired
    private GoldenDriftGate gate;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM tp_ai_golden_drift_verdict WHERE template_domain IN (?, ?)",
                DOMAIN_A, DOMAIN_B);
    }

    private static List<GoldenDriftPair> pairs(int total, int drifted) {
        java.util.ArrayList<GoldenDriftPair> list = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) {
            String control = "digest-" + i;
            String candidate = i < drifted ? "flipped-" + i : control;
            list.add(new GoldenDriftPair("route-sample-" + i, control, candidate));
        }
        return list;
    }

    private void recordRoute(String domain, String name, String controlRef, String candidateRef) {
        gate.recordComparison(domain, name, GoldenDriftGate.KIND_ROUTE,
                controlRef, candidateRef, PACK, 1, "d".repeat(64), pairs(4, 1), "route switch");
    }

    @Test
    void routeVerdictsPageReadsNewestFirstWithFullTotal() {
        // shared slot database: the ledger can carry rows outside this fixture,
        // so the whole-count claim is pinned as a delta over the pre-seed baseline
        long baseline = gate.routeVerdictsPage(null, null, null, 1, 1).total();
        recordRoute(DOMAIN_A, "fam-1", "route-old", "route-new");
        recordRoute(DOMAIN_A, "fam-1", "route-old", "route-newer");
        recordRoute(DOMAIN_A, "fam-2", "ctl-x", "cnd-x");
        GoldenDriftPageView page = gate.routeVerdictsPage(null, null, null, 1, 50);
        assertEquals(baseline + 3, page.total(), "the whole filtered count travels with the window");
        assertEquals((int) Math.min(50, baseline + 3), page.rows().size());
        assertEquals(1, page.page());
        assertEquals(50, page.pageSize());
        assertTrue(page.rows().get(0).id() > page.rows().get(2).id(),
                "rows are newest-first on the ledger id");
        assertTrue(page.rows().stream().allMatch(v -> "route".equals(v.comparisonKind())));
    }

    @Test
    void filtersNarrowDomainAndRefSides() {
        recordRoute(DOMAIN_A, "fam-1", "route-old", "route-new");
        recordRoute(DOMAIN_A, "fam-1", "route-old-2", "route-other");
        recordRoute(DOMAIN_B, "fam-1", "route-old", "route-new");
        assertEquals(1, gate.routeVerdictsPage(DOMAIN_B, null, null, 1, 50).total(),
                "the domain filter slices the ledger");
        assertEquals(1, gate.routeVerdictsPage(null, null, "route-other", 1, 50).total(),
                "the candidate-ref filter matches the one-click takeover query");
        assertEquals(2, gate.routeVerdictsPage(null, "route-old", null, 1, 50).total(),
                "the control-ref filter stays independent");
        assertEquals(1, gate.routeVerdictsPage(DOMAIN_A, "route-old", "route-new", 1, 50).total(),
                "combined filters intersect");
        assertEquals(0, gate.routeVerdictsPage(DOMAIN_A, "no-such-control", null, 1, 50).total(),
                "an unmatched ref reads as an honest empty page, never an error");
    }

    @Test
    void otherAxesNeverLeakIntoTheRouteLedger() {
        recordRoute(DOMAIN_A, "fam-1", "route-old", "route-new");
        gate.recordComparison(DOMAIN_A, "fam-head", GoldenDriftGate.KIND_HEAD,
                "head-old", "head-new", PACK, 1, "d".repeat(64), pairs(4, 0), "head switch");
        GoldenDriftPageView page = gate.routeVerdictsPage(DOMAIN_A, null, null, 1, 50);
        assertEquals(1, page.total(), "head rows stay off the route axis");
        assertEquals("route-new", page.rows().get(0).candidateRef());
    }

    @Test
    void windowPagesThroughWorstScaleAndClampsPageSize() {
        for (int i = 0; i < 5; i++) {
            recordRoute(DOMAIN_A, "fam-" + i, "ctl-" + i, "cnd-" + i);
        }
        GoldenDriftPageView first = gate.routeVerdictsPage(DOMAIN_A, null, null, 1, 2);
        assertEquals(5, first.total());
        assertEquals(2, first.rows().size(), "the window carries exactly pageSize rows");
        GoldenDriftPageView third = gate.routeVerdictsPage(DOMAIN_A, null, null, 3, 2);
        assertEquals(1, third.rows().size(), "the tail page carries the remainder");
        assertTrue(first.rows().get(0).id() > third.rows().get(0).id(),
                "page 3 continues below page 1 newest-first");
        GoldenDriftPageView clamped = gate.routeVerdictsPage(DOMAIN_A, null, null, 1, 5000);
        assertEquals(GoldenDriftGate.MAX_PAGE_SIZE, clamped.pageSize(),
                "an oversized request is clamped to the bounded window");
        assertThrows(IllegalArgumentException.class,
                () -> gate.routeVerdictsPage(DOMAIN_A, null, null, 1, 0),
                "a non-positive page size is refused, not silently widened");
    }
}
