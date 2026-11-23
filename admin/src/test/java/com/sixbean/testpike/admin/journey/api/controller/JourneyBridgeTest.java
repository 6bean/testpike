package com.sixbean.testpike.admin.journey.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.journey.api.dto.AddJourneySegmentRequest;
import com.sixbean.testpike.admin.journey.api.dto.CreateJourneyRequest;
import com.sixbean.testpike.admin.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyRunSegmentView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyRunView;
import com.sixbean.testpike.admin.journey.api.dto.JourneySegmentView;
import com.sixbean.testpike.admin.journey.api.dto.JourneyView;
import com.sixbean.testpike.admin.journey.infrastructure.remote.client.StubCoreJourneyClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Journey admin-bridge contract test , same rule family as
 * ProgramBridgeTest:
 * (1) the controller forwards whole request objects to the core client — never rebuilds
 * them field by field (field-loss incident class);
 * (2) JSON field names align one-to-one with the core internal DTO contract, because the
 * cross-service bridge matches by name;
 * (3) the admin views receive every field core returns (a dropped field = silently
 * missing display data on the plan/timeline pages).
 * StubCoreJourneyClient is the stand-in here; the real admin to core hop is covered by
 * the L3 bridge smoke at segment close.
 */
class JourneyBridgeTest {

    private final StubCoreJourneyClient client = new StubCoreJourneyClient();
    private final JourneyController controller = new JourneyController(client);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginAdmin() {
        UserContext.set(new CurrentUser(1L, "admin", "A", "系统管理员", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
    }

    @Test
    void createPassesWholeRequestObjectToCore() {
        loginAdmin();
        client.createdJourneyId(42L);
        CreateJourneyRequest request = new CreateJourneyRequest(3L, "release-lane", 7L);
        var response = controller.create(request);
        assertSame(request, client.lastCreateRequest(),
                "admin bridge must forward the whole object, no per-field copy");
        assertEquals(42L, response.getData());
    }

    @Test
    void addSegmentPassesPathIdAndWholeRequestObject() {
        loginAdmin();
        JourneySegmentView segment =
                new JourneySegmentView(5L, 1, 11L, "asset", 99L, null, "{\"caseIds\":[1]}");
        client.returnedSegment(segment);
        AddJourneySegmentRequest request =
                new AddJourneySegmentRequest(1, 11L, "asset", 99L, null, "{\"caseIds\":[1]}");
        var response = controller.addSegment(42L, request);
        assertEquals(42L, client.lastSegmentJourneyId());
        assertSame(request, client.lastSegmentRequest(),
                "segment append must be whole-object pass-through toward core JourneySegmentInput");
        assertSame(segment, response.getData());
    }

    @Test
    void runEndpointsForwardPathIds() {
        loginAdmin();
        client.returnedRun(new JourneyRunView(8L, 42L, 1, null, null, List.of()));
        controller.startRun(42L);
        assertEquals(42L, client.lastStartRunJourneyId());

        controller.getRun(8L);
        assertEquals(8L, client.lastGetRunId());

        controller.signOff(8L);
        assertEquals(8L, client.lastSignOffRunId(),
                "sign-off must forward the run id; the owner check itself lives in core");
    }

    @Test
    void readEndpointsReturnCoreViewsUntouched() {
        loginAdmin();
        JourneyView view = new JourneyView(42L, 3L, "release-lane", 7L, 1, List.of());
        client.returnedJourney(view);
        assertSame(view, controller.getJourney(42L).getData(), "single view must pass through");
        assertEquals(42L, client.lastJourneyReadId());
    }

    @Test
    void requestJsonFieldNamesAlignWithCoreContract() throws Exception {
        Map<?, ?> create = mapper.readValue(
                mapper.writeValueAsString(new CreateJourneyRequest(3L, "n", 7L)), Map.class);
        assertEquals(List.of("programId", "name", "ownerUserId"),
                create.keySet().stream().toList(),
                "must mirror core JourneyCreateInput component names");

        Map<?, ?> segment = mapper.readValue(mapper.writeValueAsString(
                new AddJourneySegmentRequest(1, 11L, "case", null, 66L, null)), Map.class);
        assertEquals(List.of("seqNo", "projectId", "targetKind", "assetRowId", "caseId",
                        "paramsOverrideJson"), segment.keySet().stream().toList(),
                "must mirror core JourneySegmentInput component names");
    }

    @Test
    void pageEndpointForwardsQueryParamsAndPassesViewThrough() {
        loginAdmin();
        JourneyPageView page = new JourneyPageView(List.of(
                new JourneyView(42L, 3L, "release-lane", 7L, 1, List.of())), 137L, 2, 50);
        client.returnedPage(page);
        assertSame(page, controller.page(3L, 2, 50, "rel").getData(),
                "page rows must pass through untouched — a dropped field silently empties the wall");
        assertEquals(3L, client.lastPageProgramId());
        assertEquals(2, client.lastPage());
        assertEquals(50, client.lastPageSize());
        assertEquals("rel", client.lastPageKeyword(),
                "keyword must reach core verbatim (name-contains filter lives in the service)");
    }

    @Test
    void pageViewJsonFieldNamesAlignWithCoreContract() throws Exception {
        Map<?, ?> page = mapper.readValue(mapper.writeValueAsString(
                new JourneyPageView(List.of(), 0L, 1, 50)), Map.class);
        assertEquals(List.of("list", "total", "page", "pageSize"), page.keySet().stream().toList(),
                "admin mirror must carry core JourneyPageView component names exactly (name-matched bridge)");
    }

    @Test
    void viewsReceiveEveryCoreInfoField() throws Exception {
        Map<?, ?> journey = mapper.readValue(mapper.writeValueAsString(
                new JourneyView(42L, 3L, "n", 7L, 1, List.of())), Map.class);
        assertEquals(List.of("id", "programId", "name", "ownerUserId", "status", "segments"),
                journey.keySet().stream().toList(), "JourneyView must carry every JourneyInfo field");

        Map<?, ?> run = mapper.readValue(mapper.writeValueAsString(
                new JourneyRunView(8L, 42L, 2, 7L, null, List.of())), Map.class);
        assertEquals(List.of("id", "journeyId", "status", "signedBy", "signedAt", "segments"),
                run.keySet().stream().toList(), "JourneyRunView must carry every JourneyRunInfo field");

        Map<?, ?> runSegment = mapper.readValue(mapper.writeValueAsString(
                new JourneyRunSegmentView(9L, 5L, 1, 11L, 77L, 3, "LAUNCH_FAILED", "boom")), Map.class);
        assertTrue(runSegment.keySet().containsAll(List.of("id", "segmentId", "seqNo", "projectId",
                        "batchId", "status", "failureCode", "failureMessage")),
                "timeline traffic-light needs batch id and failure naming to stay reachable");
    }
}
