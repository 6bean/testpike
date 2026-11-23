package com.sixbean.testpike.admin.llmconfig.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.llmconfig.api.dto.GoldenDriftPageView;
import com.sixbean.testpike.admin.llmconfig.api.dto.GoldenDriftVerdict;
import com.sixbean.testpike.admin.llmconfig.infrastructure.remote.client.StubLlmConfigClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Admin bridge contract test for the golden route-replay ledger read ,
 * sub- '). Same rule family as JourneyBridgeTest:
 * (1) the controller forwards every filter and the paging window to the core client
 * untouched — a dropped filter silently widens the ledger the page shows;
 * (2) the paged view passes back exactly what core returned — a dropped field
 * silently empties a ledger column;
 * (3) JSON component names mirror the core internal contract one-to-one, because the
 * cross-service bridge matches by name.
 * StubLlmConfigClient is the stand-in here; the real admin-to-core hop is covered by
 * the L3 probe at segment close.
 */
class LlmConfigRouteReplayBridgeTest {

    private final StubLlmConfigClient client = new StubLlmConfigClient();
    private final LlmConfigController controller = new LlmConfigController(client, null);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAdmin() {
        UserContext.set(new CurrentUser(1L, "admin", "A", "系统管理员", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
    }

    private static GoldenDriftVerdict verdict(long id, String controlRef, String candidateRef) {
        return new GoldenDriftVerdict(id, "ai.case-gen", "case-gen-system", 0L, 0L,
                null, null, null, 12, 1, new BigDecimal("8.33"), 15, true, "route replay",
                null, "route", controlRef, candidateRef);
    }

    @Test
    void routeReplaysEndpointForwardsEveryFilterAndWindowVerbatim() {
        loginAdmin();
        GoldenDriftPageView page = new GoldenDriftPageView(
                List.of(verdict(9L, "glm-4.6", "deepseek-v3")), 31L, 2, 20);
        client.returnedRoutePage(page);
        assertSame(page, controller.routeReplays("ai.case-gen", "glm-4.6", "deepseek-v3", 2, 20)
                        .getData(),
                "ledger rows must pass through untouched — a dropped field silently empties a column");
        assertEquals("ai.case-gen", client.lastRouteDomain());
        assertEquals("glm-4.6", client.lastRouteControlRef());
        assertEquals("deepseek-v3", client.lastRouteCandidateRef());
        assertEquals(2, client.lastRoutePage());
        assertEquals(20, client.lastRoutePageSize(),
                "filters and paging window must reach core verbatim (clamp lives in the gate)");
    }

    @Test
    void absentFiltersReachTheClientAsNull() {
        loginAdmin();
        controller.routeReplays(null, null, null, 1, 50);
        assertEquals(null, client.lastRouteDomain());
        assertEquals(null, client.lastRouteControlRef());
        assertEquals(null, client.lastRouteCandidateRef());
    }

    @Test
    void pageViewJsonFieldNamesAlignWithCoreContract() throws Exception {
        Map<?, ?> page = mapper.readValue(mapper.writeValueAsString(
                new GoldenDriftPageView(List.of(), 0L, 1, 50)), Map.class);
        assertEquals(List.of("rows", "total", "page", "pageSize"), page.keySet().stream().toList(),
                "admin mirror must carry core GoldenDriftPageView component names exactly");
    }

    @Test
    void verdictJsonFieldNamesAlignWithCoreContract() throws Exception {
        Map<?, ?> row = mapper.readValue(mapper.writeValueAsString(
                verdict(1L, "ctl", "cand")), Map.class);
        assertEquals(List.of("id", "templateDomain", "templateName", "candidateVersionId",
                        "controlVersionId", "packName", "packVersion", "snapshotDigest",
                        "sampleTotal", "driftedCount", "driftPercentPp", "thresholdPp",
                        "passed", "note", "createdAt", "comparisonKind", "controlRef",
                        "candidateRef"),
                row.keySet().stream().toList(),
                "admin mirror must carry every core GoldenDriftVerdict component — "
                        + "the ledger page renders drift/threshold columns straight from these");
    }
}
