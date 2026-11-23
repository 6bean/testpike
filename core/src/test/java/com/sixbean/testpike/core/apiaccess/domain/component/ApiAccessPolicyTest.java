package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessVerdict;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reachability decisions of the pass-through door, including the fail-closed default: everything the
 * generated catalog cannot judge for itself stays out of reach until a governance row says otherwise.
 */
class ApiAccessPolicyTest {

    private static final ApiEndpointParam PROJECT_ID =
            new ApiEndpointParam("projectId", ApiEndpointParam.IN_QUERY, true, "long", null);

    private ConfigCacheHolder configCache;
    private ApiAccessPolicy policy;

    @BeforeEach
    void setUp() {
        // A real cache holder rather than a stubbed reader: the door reads booleans through the
        // shared configuration reader, whose accepted spellings ("1"/"0" as well as "true"/"false")
        // are exactly what a mock would freeze away.
        configCache = new ConfigCacheHolder();
        configCache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        policy = new ApiAccessPolicy(new SystemConfigReader(configCache));
    }

    private ApiEndpointSpec read(String pattern, List<ApiEndpointParam> params) {
        return new ApiEndpointSpec("GET " + pattern, "GET", pattern, params, false, null, null, List.of());
    }

    private ApiEndpointSpec anchoredRead(String pattern) {
        return read(pattern, List.of(PROJECT_ID));
    }

    private ApiAccessGovernanceView row(String key, boolean scopeVerified, boolean reachable, String reason) {
        return new ApiAccessGovernanceView(key, scopeVerified, reachable, null, reason);
    }

    @Test
    void anchoredReadIsReachableWithNoGovernanceRow() {
        ApiEndpointSpec spec = anchoredRead("/internal/cases");
        ApiAccessVerdict verdict = policy.decide(spec, Map.of());
        assertTrue(verdict.allowed(), verdict.reason());
    }

    @Test
    void anchorlessEntryNeedsAVerifiedRow() {
        ApiEndpointSpec spec = read("/internal/fixture/dictionary", List.of());
        ApiAccessVerdict blocked = policy.decide(spec, Map.of());
        assertFalse(blocked.allowed());
        // Reader-facing refusal since the 2026-11 wording batch: it names what to do instead
        // (the page), never an internal table. Effect unchanged: an anchorless entry without a
        // verified row stays denied.
        assertTrue(blocked.reason().contains("暂未开放到对话"), blocked.reason());
        assertTrue(blocked.reason().contains("功能页面"), blocked.reason());

        ApiAccessVerdict allowed = policy.decide(spec,
                Map.of(spec.key(), row(spec.key(), true, true, null)));
        assertTrue(allowed.allowed(), allowed.reason());
    }

    @Test
    void userScopedReadIsReachableWithoutAGovernanceRow() {
        // Adjudication: an entry scoped by the acting person is not "unanchored" — the door owns
        // the identity value, so reachability needs no per-entry human registration on the read
        // side (the change side keeps requiring scope_verified, untouched).
        ApiEndpointSpec spec = read("/internal/projects", List.of(
                new ApiEndpointParam("userId", ApiEndpointParam.IN_QUERY, true, "long", null)));
        ApiAccessVerdict verdict = policy.decide(spec, Map.of());
        assertTrue(verdict.allowed(), verdict.reason());
    }

    @Test
    void registeredRetirementStillClosesAUserScopedEntry() {
        ApiEndpointSpec spec = read("/internal/projects", List.of(
                new ApiEndpointParam("userId", ApiEndpointParam.IN_QUERY, true, "long", null)));
        ApiAccessVerdict retired = policy.decide(spec,
                Map.of(spec.key(), row(spec.key(), false, false, "登录人面暂不代读")));
        assertFalse(retired.allowed());
        assertTrue(retired.reason().contains("登录人面暂不代读"), retired.reason());
    }

    @Test
    void verifiedRowCannotWidenIdentityRules() {
        // scope_verified only opens an anchorless entry; the scope guard is a separate decision made
        // per call, so a row can never stand in for membership of a project-anchored read.
        ApiEndpointSpec spec = anchoredRead("/internal/tags");
        ApiAccessVerdict verdict = policy.decide(spec,
                Map.of(spec.key(), row(spec.key(), false, true, null)));
        assertTrue(verdict.allowed(), verdict.reason());
    }

    @Test
    void changeClassIsRefused() {
        ApiEndpointSpec spec = new ApiEndpointSpec("DELETE /internal/tags/{id}", "DELETE",
                "/internal/tags/{id}", List.of(new ApiEndpointParam("id",
                        ApiEndpointParam.IN_PATH, true, "long", null)), true, null, null, List.of());
        ApiAccessVerdict verdict = policy.decide(spec, Map.of());
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("草案"), verdict.reason());
    }

    @Test
    void changeClassBeatsShapeReasonBecauseTheDraftPathIsTheActionableOne() {
        // A POST that also needs a body would otherwise be told "shape not supported", which sends
        // the caller off to reshape a body instead of to the confirmation door.
        ApiEndpointSpec spec = new ApiEndpointSpec("POST /internal/tags", "POST", "/internal/tags",
                List.of(PROJECT_ID), true,
                "endpoint reads a request body, which the read door does not compose",
                "TagCreateInput", List.of("projectId", "name"));
        ApiAccessVerdict verdict = policy.decide(spec, Map.of());
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("草案"), verdict.reason());
    }

    @Test
    void undispatchableShapeIsRefusedWithItsReason() {
        ApiEndpointSpec spec = new ApiEndpointSpec("GET /internal/x", "GET", "/internal/x",
                List.of(PROJECT_ID), false,
                "endpoint reads a request body, which the read door does not compose",
                "BodyDto", List.of("name"));
        ApiAccessVerdict verdict = policy.decide(spec, Map.of());
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("request body"), verdict.reason());
    }

    @Test
    void sensitiveFamiliesAreExcludedAndSayWhy() {
        for (String pattern : List.of("/internal/credentials/{id}", "/internal/api-tokens",
                "/internal/mcp-auth/handshake", "/internal/configs", "/internal/configs/retention",
                "/internal/llm-configs", "/internal/llm-configs/briefs", "/internal/storage/local",
                "/storage/files/**", "/internal/ai/chat/sessions", "/internal/ai-chat/drafts")) {
            ApiEndpointSpec spec = anchoredRead(pattern);
            ApiAccessVerdict verdict = policy.decide(spec, Map.of());
            assertFalse(verdict.allowed(), pattern + " must stay out of reach");
            assertTrue(verdict.reason().startsWith("该面不在直通可达集："), pattern + " -> " + verdict.reason());
        }
    }

    @Test
    void exclusionPrefixesArePreciseNotOverbroad() {
        // Adjacent business surfaces must stay reachable: a prefix that eats too much would quietly
        // shrink the door to nothing.
        for (String pattern : List.of("/internal/defects", "/internal/config-values", "/internal/aides",
                "/internal/storage-stats", "/internal/credential-requests")) {
            assertTrue(policy.decide(anchoredRead(pattern), Map.of()).allowed(),
                    pattern + " should not match an exclusion prefix");
        }
    }

    @Test
    void retiredEntryReportsTheRegisteredReason() {
        ApiEndpointSpec spec = anchoredRead("/internal/reports/daily");
        ApiAccessGovernanceView retired = row(spec.key(), true, false, "报表口径待重做，暂不代读");
        ApiAccessVerdict verdict = policy.decide(spec, Map.of(spec.key(), retired));
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("报表口径待重做"), verdict.reason());
    }

    @Test
    void unknownEntryIsRefusedWithADirection() {
        ApiAccessVerdict verdict = policy.decide(null, Map.of());
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("目录"), verdict.reason());
    }

    @Test
    void masterSwitchOffNamesThePageAndTheParameter() {
        configCache.put(ApiAccessPolicy.KEY_ENABLED, "0");
        ApiAccessVerdict verdict = policy.decide(anchoredRead("/internal/cases"), Map.of());
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("/configs"), verdict.reason());
        assertTrue(verdict.reason().contains("ai.api-access.enabled"), verdict.reason());
    }

    @Test
    void absentSwitchDefaultsToEnabled() {
        // A row nobody seeded: the shared reader hands back the door's own default,
        // and the read door's default is open (R32: AI features ship enabled).
        configCache.remove(ApiAccessPolicy.KEY_ENABLED);
        assertTrue(policy.decide(anchoredRead("/internal/cases"), Map.of()).allowed());
    }
}
