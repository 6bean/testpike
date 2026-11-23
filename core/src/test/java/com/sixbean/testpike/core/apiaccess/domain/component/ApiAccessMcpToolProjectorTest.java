package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessMcpToolView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The external tool face: what the interface catalog looks like to an agent outside the product.
 *
 * <p>The load-bearing property is that this face is a *projection*, never a list: an endpoint that
 * appears in the catalog shows up here with no code change, and one the door refuses to serve must
 * not appear here even though it exists.
 */
class ApiAccessMcpToolProjectorTest {

    private static final ApiEndpointParam PROJECT_ID =
            new ApiEndpointParam("projectId", ApiEndpointParam.IN_QUERY, true, "long", null);
    private static final ApiEndpointParam KEYWORD =
            new ApiEndpointParam("keyword", ApiEndpointParam.IN_QUERY, false, "string", null);
    private static final ApiEndpointParam PAGE =
            new ApiEndpointParam("page", ApiEndpointParam.IN_QUERY, false, "int", "1");

    private ApiEndpointCatalog catalog;
    private ApiAccessMcpToolProjector projector;

    @BeforeEach
    void setUp() {
        catalog = mock(ApiEndpointCatalog.class);
        ConfigCacheHolder cache = new ConfigCacheHolder();
        cache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        cache.put(ApiAccessMcpToolProjector.KEY_EXPOSED, "1");
        ApiAccessPolicy policy = new ApiAccessPolicy(new SystemConfigReader(cache));
        ApiAccessGovernanceRepository governance = mock(ApiAccessGovernanceRepository.class);
        when(governance.loadAll()).thenReturn(Map.of());
        projector = new ApiAccessMcpToolProjector(catalog, policy, governance,
                new SystemConfigReader(cache));
    }

    private ApiEndpointSpec read(String pattern, List<ApiEndpointParam> params) {
        return new ApiEndpointSpec("GET " + pattern, "GET", pattern, params, false, null, null, List.of());
    }

    private void catalogWith(ApiEndpointSpec... specs) {
        Map<String, ApiEndpointSpec> map = new LinkedHashMap<>();
        for (ApiEndpointSpec spec : specs) {
            map.put(spec.key(), spec);
        }
        when(catalog.endpoints()).thenReturn(map);
    }

    @Test
    void anchoredReadEntriesBecomeTools() {
        catalogWith(read("/internal/cases", List.of(PROJECT_ID, KEYWORD, PAGE)));
        List<ApiAccessMcpToolView> tools = projector.tools();
        assertEquals(1, tools.size());
        ApiAccessMcpToolView tool = tools.get(0);
        assertEquals("GET /internal/cases", tool.endpointKey());
        assertEquals("api_get_internal_cases", tool.name());
        assertTrue(tool.description().contains("projectId"), tool.description());
        assertTrue(tool.description().contains("成员"), tool.description());
    }

    @Test
    void schemaCoversExactlyTheDeclaredParameters() {
        catalogWith(read("/internal/cases", List.of(PROJECT_ID, KEYWORD, PAGE)));
        String schema = projector.tools().get(0).inputSchemaJson();
        assertTrue(schema.contains("\"projectId\""), schema);
        assertTrue(schema.contains("\"type\":\"integer\""), schema);
        assertTrue(schema.contains("\"required\":[\"projectId\"]"), schema);
        assertTrue(schema.contains("\"default\":1"), "an optional parameter with a documented default "
                + "must carry it, otherwise the agent has to guess: " + schema);
        assertTrue(schema.endsWith(",\"additionalProperties\":false}"),
                "an undeclared key must fail schema validation at the wire, not quietly disappear: "
                        + schema);
        assertFalse(schema.contains("keyword\",\"required"), schema);
    }

    /**
     * A permission-gated page face must never appear on the outward tool list: inbound MCP
     * calls carry no natural-person operator for the gate to honour, so every such call would
     * honestly refuse — projecting it would advertise a face the door cannot serve.
     */
    @Test
    void permissionGatedEntriesAreNeverProjected() {
        ApiEndpointSpec gated = read("/internal/mq-dead-letter/browse", List.of(PAGE));
        catalogWith(gated, read("/internal/cases", List.of(PROJECT_ID)));
        ApiAccessGovernanceRepository governance = mock(ApiAccessGovernanceRepository.class);
        when(governance.loadAll()).thenReturn(Map.of(
                gated.key(), new ApiAccessGovernanceView(gated.key(), true, true, null, null, null, null,
                        "perm:system:dead-letter-read"),
                "GET /internal/cases", new ApiAccessGovernanceView("GET /internal/cases",
                        true, true, null, null)));
        ConfigCacheHolder cache = new ConfigCacheHolder();
        cache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        cache.put(ApiAccessMcpToolProjector.KEY_EXPOSED, "1");
        ApiAccessMcpToolProjector live = new ApiAccessMcpToolProjector(catalog,
                new ApiAccessPolicy(new SystemConfigReader(cache)), governance,
                new SystemConfigReader(cache));
        List<ApiAccessMcpToolView> tools = live.tools();
        assertEquals(1, tools.size());
        assertEquals("GET /internal/cases", tools.get(0).endpointKey(),
                "gated browse must be absent while the ordinary anchored read stays projected");
    }

    /**
     * The shipped state of this face is closed: an outward surface that reveals endpoint names and
     * parameter shapes has to be opened by an operator, so the projector must answer with nothing at
     * all rather than a list the handler then filters — a filtered list would still be a face.
     */
    @Test
    void exposureSwitchClosedYieldsNoFace() {
        catalogWith(read("/internal/cases", List.of(PROJECT_ID)));
        ConfigCacheHolder cache = new ConfigCacheHolder();
        cache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        ApiAccessMcpToolProjector closed = new ApiAccessMcpToolProjector(catalog,
                new ApiAccessPolicy(new SystemConfigReader(cache)),
                mock(ApiAccessGovernanceRepository.class), new SystemConfigReader(cache));
        assertTrue(closed.tools().isEmpty(),
                ApiAccessMcpToolProjector.KEY_EXPOSED + " ships off: nothing may be projected");
        cache.put(ApiAccessMcpToolProjector.KEY_EXPOSED, "1");
        assertEquals(1, closed.tools().size(), "the switch is hot: opening it takes effect next read");
    }

    @Test
    void changeEntriesAreNeverProjected() {
        catalogWith(new ApiEndpointSpec("POST /internal/tags", "POST", "/internal/tags",
                List.of(PROJECT_ID), true, null, "TagCreateInput", List.of("projectId", "name")));
        assertTrue(projector.tools().isEmpty(),
                "an inbound call has no confirm channel, so the external face must not offer changes");
    }

    @Test
    void excludedAndUnanchoredEntriesStayInvisible() {
        catalogWith(read("/internal/credentials", List.of(PROJECT_ID)),
                read("/internal/dashboard/summary", List.of()));
        assertTrue(projector.tools().isEmpty(),
                "family exclusions and unverified no-anchor endpoints must not reach an agent: "
                        + projector.tools());
    }

    @Test
    void governanceVerifiedUnanchoredEntryIsProjected() {
        ApiEndpointSpec spec = read("/internal/dashboard/summary", List.of());
        catalogWith(spec);
        ApiAccessGovernanceRepository governance = mock(ApiAccessGovernanceRepository.class);
        when(governance.loadAll()).thenReturn(Map.of(spec.key(),
                new ApiAccessGovernanceView(spec.key(), true, true, "全局概览", null)));
        projector = new ApiAccessMcpToolProjector(catalog,
                new ApiAccessPolicy(new SystemConfigReader(enabledCache())), governance,
                new SystemConfigReader(enabledCache()));
        assertEquals(1, projector.tools().size());
    }

    @Test
    void namesAreStableAndOrdered() {
        catalogWith(read("/internal/zeta", List.of(PROJECT_ID)),
                read("/internal/alpha/{id}", List.of(new ApiEndpointParam("id",
                        ApiEndpointParam.IN_PATH, true, "long", null), PROJECT_ID)));
        List<ApiAccessMcpToolView> first = projector.tools();
        List<ApiAccessMcpToolView> second = projector.tools();
        assertEquals(first.stream().map(ApiAccessMcpToolView::name).toList(),
                second.stream().map(ApiAccessMcpToolView::name).toList());
        assertEquals("api_get_internal_alpha_id", first.get(0).name());
        assertEquals(List.of("api_get_internal_alpha_id", "api_get_internal_zeta"),
                first.stream().map(ApiAccessMcpToolView::name).toList(),
                "a client comparing two listings must see no shuffle");
    }

    @Test
    void longPathsGetABoundedDeterministicName() {
        String deep = "/internal/a/very/long/nested/segment/chain/that/keeps/going/cases/history";
        catalogWith(read(deep, List.of(PROJECT_ID)));
        String name = projector.tools().get(0).name();
        assertTrue(name.length() <= 67, name);
        assertTrue(name.startsWith("api_get_internal_a_very_long"), name);
        when(catalog.endpoints()).thenReturn(Map.of("GET " + deep, read(deep, List.of(PROJECT_ID))));
        assertEquals(name, projector.tools().get(0).name(), "truncated names must stay deterministic");
    }

    @Test
    void aBrandNewEndpointAppearsWithoutAnyRegistration() {
        catalogWith(read("/internal/brand/new", List.of(PROJECT_ID)));
        assertEquals(List.of("api_get_internal_brand_new"),
                projector.tools().stream().map(ApiAccessMcpToolView::name).toList());
        // ...and a second one added later is there too: the face is recomputed, never maintained.
        catalogWith(read("/internal/brand/new", List.of(PROJECT_ID)),
                read("/internal/brand/later", List.of(PROJECT_ID)));
        assertEquals(2, projector.tools().size());
    }

    private ConfigCacheHolder enabledCache() {
        ConfigCacheHolder cache = new ConfigCacheHolder();
        cache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        cache.put(ApiAccessMcpToolProjector.KEY_EXPOSED, "1");
        return cache;
    }
}
