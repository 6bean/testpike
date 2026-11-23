package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts of the acting-person anchor re-stamp (adjudication: identity-scoped pass-through
 * entries are reachable AND server-owned — "my projects" never needs, and never honours, a
 * model-named user id).
 */
class ApiAccessIdentityAnchorTest {

    private static final ApiEndpointParam USER_ID =
            new ApiEndpointParam("userId", ApiEndpointParam.IN_QUERY, true, "long", null);
    private static final ApiEndpointParam PROJECT_ID =
            new ApiEndpointParam("projectId", ApiEndpointParam.IN_QUERY, true, "long", null);

    private static ApiEndpointSpec read(String pattern, List<ApiEndpointParam> params) {
        return new ApiEndpointSpec("GET " + pattern, "GET", pattern, params, false, null, null, List.of());
    }

    @Test
    void fillsTheAnchorWhenTheCallerNamedNothing() {
        ApiEndpointSpec spec = read("/internal/projects", List.of(USER_ID));
        Map<String, Object> out = ApiAccessIdentityAnchor.restamp(spec, Map.of(), 66948L);
        assertEquals(66948L, out.get("userId"));
    }

    @Test
    void overwritesAnyClaimedPersonWithTheTrustedOperator() {
        ApiEndpointSpec spec = read("/internal/projects", List.of(USER_ID));
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("userId", 999L);
        Map<String, Object> out = ApiAccessIdentityAnchor.restamp(spec, claimed, 66948L);
        assertEquals(66948L, out.get("userId"), "a payload claim never survives the stamp");
    }

    @Test
    void numericStringClaimIsAlsoOverwritten() {
        ApiEndpointSpec spec = read("/internal/projects", List.of(USER_ID));
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("userId", "999");
        Map<String, Object> out = ApiAccessIdentityAnchor.restamp(spec, claimed, 66948L);
        assertEquals(66948L, out.get("userId"));
    }

    @Test
    void dropsOnlyTheDialogSliceStrayProjectKeyOnUserScopedEntries() {
        // The conversation link stamps the session project into every pass-through read before
        // dispatch; on a user-scoped entry that key is foreign and the door's tolerance is the
        // narrowest possible: only the project-family keys, only when the entry has no project anchor.
        ApiEndpointSpec spec = read("/internal/projects", List.of(USER_ID));
        Map<String, Object> withStray = new LinkedHashMap<>();
        withStray.put("projectId", 195855L);
        Map<String, Object> out = ApiAccessIdentityAnchor.restamp(spec, withStray, 66948L);
        assertFalse(out.containsKey("projectId"), "foreign session slice key must not reach the guard");
        assertEquals(66948L, out.get("userId"));

        Map<String, Object> otherStray = new LinkedHashMap<>();
        otherStray.put("sortDir", "asc");
        otherStray.put("projectId", 5L);
        Map<String, Object> out2 = ApiAccessIdentityAnchor.restamp(spec, otherStray, 7L);
        assertTrue(out2.containsKey("sortDir"), "every other undeclared key rides on for the guard to refuse");
    }

    @Test
    void projectAnchoredEntriesKeepTheirProjectKeyForTheMembershipGuard() {
        ApiEndpointSpec spec = read("/internal/tags", List.of(PROJECT_ID, USER_ID));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("projectId", 195855L);
        Map<String, Object> out = ApiAccessIdentityAnchor.restamp(spec, args, 66948L);
        assertEquals(195855L, out.get("projectId"), "project anchor stays caller-named; the guard judges it");
        assertEquals(66948L, out.get("userId"));
    }

    @Test
    void entriesWithoutAnyUserAnchorRideOnByteIdentical() {
        ApiEndpointSpec plain = read("/internal/projects", List.of());
        Map<String, Object> args = Map.of("projectId", 1L);
        assertSame(args, ApiAccessIdentityAnchor.restamp(plain, args, 66948L));
    }

    @Test
    void allIdentitySpellingsAreRecognizedAndMatchedClaimsStay() {
        for (String name : List.of("userId", "uid", "operatorId", "actorId")) {
            ApiEndpointSpec spec = read("/internal/x", List.of(
                    new ApiEndpointParam(name, ApiEndpointParam.IN_QUERY, true, "long", null)));
            assertEquals(name, spec.userAnchor(), name + " is a person anchor");
            Map<String, Object> exact = Map.of(name, 66948L);
            assertSame(exact, ApiAccessIdentityAnchor.restamp(spec, exact, 66948L),
                    "an already-honest bag is never rewritten");
        }
    }

    // ------------------------------------------------------------------
    // project-slice conformance (dialog incident: the blind stamp poisoned every entry
    // whose declared shape does not consume the literal key it was stamped under)
    // ------------------------------------------------------------------

    @Test
    void stampedSliceIsDroppedOnEntriesWithoutProjectScope() {
        // The reported case: GET /internal/prompt-templates/families declares only `domain`;
        // an anchored conversation used to die on the stray-argument guard for every such entry.
        ApiEndpointSpec families = read("/internal/prompt-templates/families", List.of(
                new ApiEndpointParam("domain", ApiEndpointParam.IN_QUERY, false, "String", null)));
        Map<String, Object> stamped = new LinkedHashMap<>();
        stamped.put("domain", "test_case");
        stamped.put("projectId", 195951L);
        Map<String, Object> out = ApiAccessIdentityAnchor.conformProjectSlice(families, stamped);
        assertFalse(out.containsKey("projectId"), "the session plumbing must not poison the read");
        assertEquals("test_case", out.get("domain"), "declared parameters ride on untouched");
    }

    @Test
    void stampedSliceIsTransferredOntoTheDeclaredAnchorName() {
        ApiEndpointSpec plural = read("/internal/batches", List.of(
                new ApiEndpointParam("projectIds", ApiEndpointParam.IN_QUERY, false, "List", null)));
        assertEquals("projectIds", plural.projectAnchor(), "a plural spelling is still a project anchor");
        Map<String, Object> out = ApiAccessIdentityAnchor.conformProjectSlice(
                plural, Map.of("projectId", 195951L));
        assertEquals(195951L, out.get("projectIds"), "the slice answers under the entry's own name");
        assertFalse(out.containsKey("projectId"), "the stamp key itself is not declared and is dropped");

        ApiEndpointSpec pid = read("/internal/x", List.of(
                new ApiEndpointParam("pid", ApiEndpointParam.IN_QUERY, true, "long", null)));
        Map<String, Object> pidOut = ApiAccessIdentityAnchor.conformProjectSlice(
                pid, Map.of("projectId", 7L));
        assertEquals(7L, pidOut.get("pid"));
    }

    @Test
    void transferredSliceOverwritesAnyPayloadClaimOnTheAnchorName() {
        // Same posture as the person anchor: the conversation segment decides the project a
        // pass-through read is anchored to; what the payload named for projectIds is overwritten.
        ApiEndpointSpec plural = read("/internal/batches", List.of(
                new ApiEndpointParam("projectIds", ApiEndpointParam.IN_QUERY, false, "List", null)));
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("projectId", 195951L);
        claimed.put("projectIds", List.of(999L));
        Map<String, Object> out = ApiAccessIdentityAnchor.conformProjectSlice(plural, claimed);
        assertEquals(195951L, out.get("projectIds"), "server truth wins over the payload claim");
    }

    @Test
    void conformanceIsNarrowlyScopedToStrayKeysStillRefuse() {
        ApiEndpointSpec families = read("/internal/prompt-templates/families", List.of());
        Map<String, Object> withStray = new LinkedHashMap<>();
        withStray.put("projectId", 195951L);
        withStray.put("sortDir", "asc");
        Map<String, Object> out = ApiAccessIdentityAnchor.conformProjectSlice(families, withStray);
        assertFalse(out.containsKey("projectId"));
        assertTrue(out.containsKey("sortDir"), "only the exact stamped key is forgiven — the rest still dies on the guard");

        // A model-claimed plural key on a scopeless entry is NOT plumbing: it stays and refuses.
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("projectIds", List.of(9L));
        assertSame(claimed, ApiAccessIdentityAnchor.conformProjectSlice(families, claimed),
                "no stamp key present means nothing is conformed and nothing is silently dropped");

        // Native projectId anchors (the overwhelmingly common spelling) ride on untouched.
        ApiEndpointSpec tags = read("/internal/tags", List.of(PROJECT_ID));
        Map<String, Object> native1 = Map.of("projectId", 5L);
        assertSame(native1, ApiAccessIdentityAnchor.conformProjectSlice(tags, native1));
    }
}
