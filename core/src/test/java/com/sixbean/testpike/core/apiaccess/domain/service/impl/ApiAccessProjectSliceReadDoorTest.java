package com.sixbean.testpike.core.apiaccess.domain.service.impl;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadResult;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteRequest;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteResult;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.apiaccess.domain.component.ApiAccessPermissionGate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The read door end to end, from a stamped dialog argument bag to the composed wire call.
 *
 * <p>Why this file exists: the 2026-11 prompt-template incident lived between components.
 * The dialog link stamps the session segment under one literal key, the identity anchor had a
 * narrow tolerance only for person-scoped entries, and the request factory refuses undeclared
 * arguments — each piece tested green in isolation while the composition poisoned every
 * reachable entry that did not declare the literal key (113 scopeless reads and 103 entries
 * spelling their anchor projectIds/pid were dead from any project-anchored conversation).
 * These cases run the real chain (policy → anchors → conformance → scope guard → factory →
 * loopback call) against a local fixture server, so the next shape mismatch dies HERE first.
 *
 * <p>The only stand-ins are the ones every door test uses (registry lookups, quota, row
 * bridges, audit); the argument-consumption chain itself is the production classes, and the
 * dial target is a real HTTP server recording the actual query string and body.
 */
class ApiAccessProjectSliceReadDoorTest {

    private static final long OPERATOR = 66948L;
    private static final long SLICE = 195951L;
    private static final String DLQ_READ_PERM = "perm:system:dead-letter-read";

    private static HttpServer target;
    private static String capturedQuery;
    private static String capturedMethod;
    private static String capturedBody;

    private final ApiAccessCatalogTestHarness harness = new ApiAccessCatalogTestHarness();
    private final com.sixbean.testpike.core.rbac.domain.service.RoleService roleService =
            mock(com.sixbean.testpike.core.rbac.domain.service.RoleService.class);

    @BeforeAll
    static void startTarget() throws Exception {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/internal", exchange -> {
            capturedQuery = exchange.getRequestURI().getRawQuery();
            capturedMethod = exchange.getRequestMethod();
            capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] json = "{\"code\":0,\"msg\":\"ok\",\"data\":[{\"name\":\"case_gen\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(json);
            }
        });
        target.start();
    }

    @AfterAll
    static void stopTarget() {
        target.stop(0);
    }

    @BeforeEach
    void wireDoor() {
        harness.wireDoor("http://127.0.0.1:" + target.getAddress().getPort(), true,
                new ApiAccessPermissionGate(roleService));
        when(harness.governance.loadAll()).thenReturn(Map.of());
        when(harness.quota.tryClaim(anyString())).thenReturn(null);
        when(harness.rowOwnership.problem(any(), anyLong(), any())).thenReturn(null);
        when(harness.rowOwnership.membershipProblem(any(), anyLong())).thenReturn(null);
        when(harness.admins.adminUserIds()).thenReturn(List.of(OPERATOR));
    }

    @AfterEach
    void resetCapture() {
        capturedQuery = null;
        capturedMethod = null;
        capturedBody = null;
    }

    private static ApiEndpointParam param(String name, boolean required) {
        return new ApiEndpointParam(name, ApiEndpointParam.IN_QUERY, required, "long", null);
    }

    private static ApiEndpointSpec get(String path, List<ApiEndpointParam> params) {
        return new ApiEndpointSpec("GET " + path, "GET", path, params, false, null, null, List.of());
    }

    /** The reported entry shape: a global asset declaring only a domain filter. */
    private static ApiEndpointSpec families() {
        return get("/internal/prompt-templates/families",
                List.of(new ApiEndpointParam("domain", ApiEndpointParam.IN_QUERY, false, "String", null)));
    }

    private void allowGlobalEntry(ApiEndpointSpec spec) {
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        when(harness.governance.loadAll()).thenReturn(Map.of(spec.key(),
                new ApiAccessGovernanceView(spec.key(), true, true, null, null)));
    }

    @Test
    void scopelessEntrySurvivesTheStampedSessionSlice() {
        // The user-visible failure: asking "how many prompt templates" from an anchored
        // conversation died on "projectId is not a declared parameter" of this entry.
        ApiEndpointSpec spec = families();
        allowGlobalEntry(spec);
        Map<String, Object> stamped = new LinkedHashMap<>();
        stamped.put("projectId", SLICE);
        ApiAccessReadResult result = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(spec.key(), stamped));
        assertTrue(result.served(), result.reason());
        assertNull(capturedQuery, "the stamped plumbing key must never reach the wire");
    }

    @Test
    void declaredParametersRideOnUntouched() {
        ApiEndpointSpec spec = families();
        allowGlobalEntry(spec);
        Map<String, Object> stamped = new LinkedHashMap<>();
        stamped.put("domain", "test_case");
        stamped.put("projectId", SLICE);
        ApiAccessReadResult result = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(spec.key(), stamped));
        assertTrue(result.served(), result.reason());
        assertEquals("domain=test_case", capturedQuery, "the entry sees exactly what it declares");
    }

    @Test
    void pluralAnchorEntryIsServedUnderItsOwnDeclaredName() {
        ApiEndpointSpec spec = get("/internal/batches", List.of(param("projectIds", false)));
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        Map<String, Object> stamped = new LinkedHashMap<>();
        stamped.put("projectId", SLICE);
        ApiAccessReadResult result = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(spec.key(), stamped));
        assertTrue(result.served(), result.reason());
        assertEquals("projectIds=" + SLICE, capturedQuery,
                "the slice answers under the anchor the endpoint declares");
    }

    @Test
    void pidAnchorEntryIsServedAndPayloadClaimsOnTheAnchorAreOverwritten() {
        ApiEndpointSpec spec = get("/internal/x", List.of(param("pid", true)));
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("projectId", SLICE);
        claimed.put("pid", 999L);
        ApiAccessReadResult result = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(spec.key(), claimed));
        assertTrue(result.served(), result.reason());
        assertEquals("pid=" + SLICE, capturedQuery, "server slice truth wins over the payload claim");
    }

    @Test
    void toleranceStaysNarrowAnyOtherUndeclaredArgumentStillRefuses() {
        ApiEndpointSpec spec = families();
        allowGlobalEntry(spec);
        Map<String, Object> smuggled = new LinkedHashMap<>();
        smuggled.put("projectId", SLICE);
        smuggled.put("sortDir", "asc");
        ApiAccessReadResult result = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(spec.key(), smuggled));
        assertFalse(result.served());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("sortDir"), result.reason());
        assertTrue(result.reason().contains("未声明"), result.reason());
    }

    @Test
    void modelClaimedPluralOnScopelessEntryIsNotMistakenForTheStamp() {
        // Only the exact stamped key is plumbing. A claimed projectIds on an entry with no
        // project scope is a model mistake (or an attack) and must still be heard as a refusal,
        // so the whitelist keeps teaching instead of silently ignoring filters nobody consume.
        ApiEndpointSpec spec = families();
        allowGlobalEntry(spec);
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("projectIds", List.of(9L));
        ApiAccessReadResult result = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(spec.key(), claimed));
        assertFalse(result.served());
        assertTrue(result.reason().contains("projectIds"), result.reason());
    }

    @Test
    void nativeProjectIdAnchorKeepsThePreExistingPosture() {
        ApiEndpointSpec spec = get("/internal/tags", List.of(param("projectId", true)));
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        ApiAccessReadResult result = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(
                        spec.key(), Map.of("projectId", SLICE)));
        assertTrue(result.served(), result.reason());
        assertEquals("projectId=" + SLICE, capturedQuery);
    }

    @Test
    void changeLandingStampsTheBodyAnchorUnderTheDeclaredName() {
        // The write side carries the same latent class: the landing re-stamped the literal
        // projectId, so an entry whose body declares projectIds refused its own server stamp.
        ApiEndpointSpec spec = new ApiEndpointSpec("PUT /internal/batch-config", "PUT",
                "/internal/batch-config", List.of(), true, null, "BatchConfigPayload",
                List.of("projectIds", "memo"));
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        when(harness.governance.loadAll()).thenReturn(Map.of(spec.key(),
                new ApiAccessGovernanceView(spec.key(), true, true, null, null)));
        ApiAccessWriteResult result = harness.door.landConfirmedChange(OPERATOR, SLICE,
                new ApiAccessWriteRequest(spec.key(), new LinkedHashMap<>(Map.of("memo", "hi"))));
        assertTrue(result.landed(), result.reason());
        assertNotNull(capturedBody);
        assertTrue(capturedBody.contains("\"projectIds\":" + SLICE), capturedBody);
        assertTrue(capturedBody.contains("\"memo\":\"hi\""), capturedBody);
    }

    // ------------------------------------------------------------------
    // third ownership face: the functional permission the serving page enforces
    // ------------------------------------------------------------------

    @Test
    void permissionGatedEntryServesHoldersAndRefusesOthersNamingThePermission() {
        ApiEndpointSpec spec = get("/internal/mq-dead-letter/browse", List.of(
                new ApiEndpointParam("page", ApiEndpointParam.IN_QUERY, false, "int", null)));
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        when(harness.governance.loadAll()).thenReturn(Map.of(spec.key(),
                new ApiAccessGovernanceView(spec.key(), true, true, null, null, null, null, DLQ_READ_PERM)));

        when(roleService.permsOfUser(OPERATOR)).thenReturn(java.util.Set.of("perm:other"));
        ApiAccessReadResult outsider = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(
                        spec.key(), Map.of("page", 1)));
        assertFalse(outsider.served());
        assertTrue(outsider.reason().contains(DLQ_READ_PERM), outsider.reason());
        assertTrue(outsider.reason().contains("/roles"), outsider.reason());

        when(roleService.permsOfUser(OPERATOR)).thenReturn(java.util.Set.of(DLQ_READ_PERM));
        ApiAccessReadResult holder = harness.door.read(OPERATOR, null,
                new com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessReadRequest(
                        spec.key(), Map.of("page", 1)));
        assertTrue(holder.served(), holder.reason());
    }

    @Test
    void scopelessChangeEntryLandsThroughItsPagePermissionAlone() {
        // The dead-letter void face: ledger rows carry no project anchor, so the ONLY honest
        // ownership for this change is the permission the page's own button enforces. The
        // confirmation card and the write switch (checked upstream) stay exactly as they were.
        ApiEndpointSpec spec = new ApiEndpointSpec("POST /internal/mq-dead-letter/void", "POST",
                "/internal/mq-dead-letter/void",
                List.of(new ApiEndpointParam("ledgerId", ApiEndpointParam.IN_QUERY, true, "long", null),
                        new ApiEndpointParam("reason", ApiEndpointParam.IN_QUERY, true, "String", null)),
                true, null, null, List.of());
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        when(harness.governance.loadAll()).thenReturn(Map.of(spec.key(),
                new ApiAccessGovernanceView(spec.key(), true, true, null, null, null, null,
                        "perm:system:dead-letter-void")));

        when(roleService.permsOfUser(OPERATOR)).thenReturn(java.util.Set.of());
        ApiAccessWriteResult outsider = harness.door.landConfirmedChange(OPERATOR, SLICE,
                new ApiAccessWriteRequest(spec.key(), Map.of("ledgerId", 7L, "reason", "stale")));
        assertFalse(outsider.landed());
        assertTrue(outsider.reason().contains("perm:system:dead-letter-void"), outsider.reason());

        when(roleService.permsOfUser(OPERATOR)).thenReturn(java.util.Set.of("perm:system:dead-letter-void"));
        ApiAccessWriteResult holder = harness.door.landConfirmedChange(OPERATOR, SLICE,
                new ApiAccessWriteRequest(spec.key(), Map.of("ledgerId", 7L, "reason", "stale")));
        assertTrue(holder.landed(), holder.reason());
        assertEquals("POST", capturedMethod);
        assertNotNull(capturedQuery);
        assertTrue(capturedQuery.contains("ledgerId=7"), capturedQuery);
    }

    @Test
    void unanchoredUngatedChangeEntryStillRefusesTheOldWay() {
        // The new permission path must not become a back door: without a registered permission
        // the carrier rule stays — a change that cannot attribute a project is refused outright.
        ApiEndpointSpec spec = new ApiEndpointSpec("POST /internal/misc/reset-counter", "POST",
                "/internal/misc/reset-counter", List.of(), true, null, null, List.of());
        when(harness.catalog.find(spec.key())).thenReturn(spec);
        when(harness.governance.loadAll()).thenReturn(Map.of(spec.key(),
                new ApiAccessGovernanceView(spec.key(), true, true, null, null)));
        ApiAccessWriteResult result = harness.door.landConfirmedChange(OPERATOR, SLICE,
                new ApiAccessWriteRequest(spec.key(), Map.of()));
        assertFalse(result.landed());
        assertTrue(result.reason().contains("不带项目归属锚"), result.reason());
    }
}
