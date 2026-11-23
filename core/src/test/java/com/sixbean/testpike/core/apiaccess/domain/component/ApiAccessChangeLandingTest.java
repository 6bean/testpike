package com.sixbean.testpike.core.apiaccess.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.apiaccess.ApiAccessFixtureController;
import com.sixbean.testpike.core.apiaccess.TagPayloadFixture;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessVerdict;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessWriteTarget;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointParam;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Change landing rules: what a confirmed card is allowed to turn into a real call.
 *
 * <p>The two properties that matter are that writes are opt-in (governance row + switch, both named
 * in a refusal) and that a payload can only ever be composed from fields the door itself declares.
 */
class ApiAccessChangeLandingTest {

    private static final ApiEndpointParam PROJECT_ID =
            new ApiEndpointParam("projectId", ApiEndpointParam.IN_QUERY, true, "long", null);

    private ConfigCacheHolder configCache;
    private ApiAccessPolicy policy;
    private final ApiAccessRequestFactory factory = new ApiAccessRequestFactory(new ObjectMapper());

    @BeforeEach
    void setUp() {
        configCache = new ConfigCacheHolder();
        configCache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        configCache.put(ApiAccessPolicy.KEY_WRITE_ENABLED, "1");
        policy = new ApiAccessPolicy(new SystemConfigReader(configCache));
    }

    private ApiEndpointSpec changeEntry(String pattern, String bodyType, List<String> bodyFields,
                                        List<ApiEndpointParam> params) {
        return new ApiEndpointSpec("POST " + pattern, "POST", pattern, params, true, null,
                bodyType, bodyFields);
    }

    private ApiAccessGovernanceView verified(String key) {
        return new ApiAccessGovernanceView(key, true, true, null, null);
    }

    /**
     * The shipped spelling of a switch row is "1"/"0", not "true"/"false" (the migration seeds it
     * that way and the configuration page edits that value). Reading it any other way leaves an
     * opened switch that never opens — the first version of the change door did exactly that, so
     * this case is the fence against the whole bug class, for both doors.
     */
    @Test
    void numericSwitchSpellingsAreHonouredByBothDoors() {
        configCache.put(ApiAccessPolicy.KEY_ENABLED, "1");
        configCache.put(ApiAccessPolicy.KEY_WRITE_ENABLED, "1");
        assertTrue(policy.enabled());
        assertTrue(policy.writeEnabled(), "an opened change switch must actually open the door");

        configCache.put(ApiAccessPolicy.KEY_ENABLED, "0");
        configCache.put(ApiAccessPolicy.KEY_WRITE_ENABLED, "0");
        assertFalse(policy.enabled());
        assertFalse(policy.writeEnabled());

        // A missing row keeps each door's own documented default: reads open, writes closed.
        configCache.remove(ApiAccessPolicy.KEY_ENABLED);
        configCache.remove(ApiAccessPolicy.KEY_WRITE_ENABLED);
        assertTrue(policy.enabled());
        assertFalse(policy.writeEnabled());
    }

    @Test
    void writeSwitchOffRefusesAndNamesTheKey() {
        configCache.put(ApiAccessPolicy.KEY_WRITE_ENABLED, "0");
        ApiEndpointSpec spec = changeEntry("/internal/tags", "TagPayloadFixture",
                List.of("projectId", "name"), List.of());
        ApiAccessVerdict verdict = policy.decide(spec, Map.of(spec.key(), verified(spec.key())), true);
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains(ApiAccessPolicy.KEY_WRITE_ENABLED), verdict.reason());
        assertTrue(verdict.reason().contains("/configs"), verdict.reason());
    }

    @Test
    void changeNeedsAGovernanceRowBeforeItCanLand() {
        ApiEndpointSpec spec = changeEntry("/internal/tags", "TagPayloadFixture",
                List.of("projectId", "name"), List.of());
        ApiAccessVerdict verdict = policy.decide(spec, Map.of(), true);
        assertFalse(verdict.allowed());
        // Reader-facing since 2026-11: the refusal states the effect (not open to the dialog)
        // and the endpoint key as anchor; the internal table name no longer reaches the screen.
        assertTrue(verdict.reason().contains("暂未开放到对话"), verdict.reason());
        assertFalse(verdict.reason().contains(ApiAccessGovernanceRepository.TABLE),
                "the internal table name must not reach the screen: " + verdict.reason());
        assertTrue(policy.decide(spec, Map.of(spec.key(), verified(spec.key())), true).allowed());
    }

    @Test
    void readEntryCannotLandThroughTheChangeDoor() {
        ApiEndpointSpec read = new ApiEndpointSpec("GET /internal/tags", "GET", "/internal/tags",
                List.of(PROJECT_ID), false, null, null, List.of());
        ApiAccessVerdict verdict = policy.decide(read, Map.of(read.key(), verified(read.key())), true);
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("只读条目"), verdict.reason());
    }

    @Test
    void changeEntryStillRefusesThePlainReadDoor() {
        ApiEndpointSpec spec = changeEntry("/internal/tags", "TagPayloadFixture",
                List.of("projectId", "name"), List.of());
        ApiAccessVerdict verdict = policy.decide(spec, Map.of(spec.key(), verified(spec.key())), false);
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("草案"), verdict.reason());
    }

    /**
     * A delete addressed entirely by path takes no payload — the most ordinary change an endpoint
     * can be. The first version of the shape guard demanded a declared body and so refused it; this
     * case pins the difference between "no payload needed" and "payload I cannot compose".
     */
    @Test
    void bodylessChangePassesTheShapeGuardAndStillNeedsRegistration() {
        ApiEndpointSpec spec = new ApiEndpointSpec("DELETE /internal/api-spec-subscriptions/{id}",
                "DELETE", "/internal/api-spec-subscriptions/{id}",
                List.of(new ApiEndpointParam("id", ApiEndpointParam.IN_PATH, true, "long", null)),
                true, null, null, List.of());
        ApiAccessVerdict unregistered = policy.decide(spec, Map.of(), true);
        assertFalse(unregistered.allowed());
        // Reader-facing refusal naming the effect, not the registration plumbing - and never
        // a payload complaint for an entry that needs no payload.
        assertTrue(unregistered.reason().contains("暂未开放到对话"),
                "the refusal must be about the closed face, not about a payload it never needed: "
                        + unregistered.reason());
        assertTrue(policy.decide(spec, Map.of(spec.key(), verified(spec.key())), true).allowed());
    }

    @Test
    void headerReadingEntryIsNeverComposable() {
        ApiEndpointSpec spec = new ApiEndpointSpec("PUT /internal/x", "PUT", "/internal/x",
                List.of(PROJECT_ID), true,
                "endpoint reads a request header, which the door cannot provide", null, List.of());
        ApiAccessVerdict verdict = policy.decide(spec, Map.of(spec.key(), verified(spec.key())), true);
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().contains("无法安全组装载荷"), verdict.reason());
    }

    @Test
    void excludedFamilyCannotLandEvenWhenVerified() {
        ApiEndpointSpec spec = changeEntry("/internal/credentials/{id}", "Payload",
                List.of("value"), List.of(PROJECT_ID));
        ApiAccessVerdict verdict = policy.decide(spec, Map.of(spec.key(), verified(spec.key())), true);
        assertFalse(verdict.allowed());
        assertTrue(verdict.reason().startsWith("该面不在直通可达集："), verdict.reason());
    }

    @Test
    void bodyFieldsAreReadOffTheDeclaredPayloadType() throws Exception {
        Method method = ApiAccessFixtureController.class.getDeclaredMethod("createTag",
                TagPayloadFixture.class);
        ApiEndpointSpec spec = ApiEndpointIntrospector.introspect(method, "POST", "/internal/fixture/tags");
        assertEquals("TagPayloadFixture", spec.bodyType());
        assertEquals(List.of("projectId", "name", "type"), spec.bodyFields());
        assertEquals(ApiEndpointSpec.ANCHOR_BODY, spec.anchorCarrier(),
                "a payload-borne project anchor must be recognised, otherwise the landing cannot scope it");
        assertTrue(spec.unsupportedBodyOnly(), "the read door refuses it, the change door composes it");
    }

    @Test
    void bodyIsComposedOnlyFromDeclaredFields() throws Exception {
        ApiEndpointSpec spec = changeEntry("/internal/tags", "TagPayloadFixture",
                List.of("projectId", "name", "type"), List.of());
        ApiAccessWriteTarget target = factory.buildWrite(spec,
                Map.of("projectId", 7, "name", "直通落地", "type", 4));
        assertEquals("POST", target.httpMethod());
        assertEquals("/internal/tags", target.requestUri());
        assertTrue(target.bodyJson().contains("\"projectId\":7"), target.bodyJson());
        assertTrue(target.bodyJson().contains("\"name\":\"直通落地\""), target.bodyJson());
        assertEquals(List.of("name", "projectId", "type"), sorted(target.usedArguments()));
    }

    @Test
    void undeclaredBodyFieldIsRefused() throws Exception {
        ApiEndpointSpec spec = changeEntry("/internal/tags", "TagPayloadFixture",
                List.of("projectId", "name", "type"), List.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.buildWrite(spec, Map.of("projectId", 7, "name", "n", "status", 9)));
        assertTrue(ex.getMessage().contains("status"), ex.getMessage());
        assertTrue(ex.getMessage().contains("TagPayloadFixture"), ex.getMessage());
    }

    @Test
    void emptyPayloadIsRefusedWithTheUsableFieldList() {
        ApiEndpointSpec spec = changeEntry("/internal/tags", "TagPayloadFixture",
                List.of("projectId", "name", "type"), List.of());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.buildWrite(spec, Map.of()));
        assertTrue(ex.getMessage().contains("projectId、name、type"), ex.getMessage());
    }

    @Test
    void bodylessChangeSendsNoPayload() {
        ApiEndpointSpec spec = new ApiEndpointSpec("DELETE /internal/tags/{id}", "DELETE",
                "/internal/tags/{id}",
                List.of(new ApiEndpointParam("id", ApiEndpointParam.IN_PATH, true, "long", null)),
                true, null, null, List.of());
        ApiAccessWriteTarget target = factory.buildWrite(spec, Map.of("id", 12));
        assertNull(target.bodyJson());
        assertEquals("/internal/tags/12", target.requestUri());
    }

    @Test
    void pathAndQueryAnchorsStayAddressableInAChange() {
        ApiEndpointSpec spec = new ApiEndpointSpec("PUT /internal/tags/{id}", "PUT", "/internal/tags/{id}",
                List.of(new ApiEndpointParam("id", ApiEndpointParam.IN_PATH, true, "long", null),
                        PROJECT_ID),
                true, null, "RenamePayload", List.of("name"));
        ApiAccessWriteTarget target = factory.buildWrite(spec,
                Map.of("id", 3, "projectId", 9, "name", "新名"));
        assertEquals("/internal/tags/3?projectId=9", target.requestUri());
        assertTrue(target.bodyJson().contains("\"name\""), target.bodyJson());
        assertEquals(ApiEndpointSpec.ANCHOR_PATH,
                spec.param("id").in(), "the path anchor stays a path anchor");
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().toList();
    }
}
