package com.sixbean.testpike.core.appauto.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.UiActionContract;
import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Factory app-component pack seed contract (design 5.6: login / logout / permission
 * handling / pull-to-refresh / common form fill shipped out of the box).
 *
 * <p>Verification faces, each pinned against the live slot database (no mock — the
 * seed only counts when the migration actually landed):
 * <ol>
 *   <li>the factory owner project row exists (tp_asset.project_id is NOT NULL, so the
 *       pack needs one owner; cross-project readers gain discoverability through the
 *       asset-chain head projection, never write access);</li>
 *   <li>exactly five pack rows, published head rows (status=1, version 1, no chain
 *       predecessor);</li>
 *   <li>payload shapes follow the frontend publish contract (utils/assetRef.ts:
 *       case_fragment = step-array, action_component = one step object) and every
 *       verb stays inside the single-sourced IR verb list;</li>
 *   <li>credential surfaces reference names only ({@code {{CRED:key}}}) — no secret
 *       value may ever ship inside a factory payload (red line R1).</li>
 * </ol>
 */
@SpringBootTest(classes = CoreApplication.class)
class AppautoFactoryComponentPackTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String FACTORY_PROJECT_CODE = "SYS";

    /** The five shipped component names (single source with migration V482). */
    static final List<String> PACK_NAMES = List.of(
            "内置登录", "内置登出", "内置权限处理", "内置下拉刷新", "内置常见表单填充");

    /** Step-bearing kinds only; login_recipe / gen_function are not component-pack kinds. */
    static final Set<String> ALLOWED_KINDS = Set.of("case_fragment", "action_component");

    /** Interpolation namespaces: the CRED/VAR/ENV/DATA/PACK set the editor completion offers. */
    private static final Pattern NAMESPACE_REF =
            Pattern.compile("^\\{\\{(CRED|VAR|ENV|DATA|PACK):[^{}]+}}$");

    private Map<String, Object> factoryProjectRow() {
        return jdbcTemplate.queryForMap(
                "select id, name, code from tp_project where code = ?", FACTORY_PROJECT_CODE);
    }

    @Test
    void factoryOwnerProjectIsSeeded() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select id, name, code, status from tp_project where code = ?",
                FACTORY_PROJECT_CODE);
        assertEquals(1, rows.size(),
                "factory owner project row (code=" + FACTORY_PROJECT_CODE + ") must be seeded exactly once");
        assertEquals(1, ((Number) rows.get(0).get("status")).intValue(),
                "factory owner project must be active");
    }

    @Test
    void packRowsArePublishedFirstVersionHeads() {
        long factoryId = ((Number) factoryProjectRow().get("id")).longValue();
        for (String name : PACK_NAMES) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    select kind, status, asset_version, prev_asset_id, payload_json
                    from tp_asset
                    where project_id = ? and name = ?
                    """, factoryId, name);
            assertEquals(1, rows.size(), "factory component row missing or duplicated: " + name);
            Map<String, Object> row = rows.get(0);
            assertTrue(ALLOWED_KINDS.contains((String) row.get("kind")),
                    name + ": kind must be a step-bearing asset kind, got " + row.get("kind"));
            assertEquals(1, ((Number) row.get("status")).intValue(), name + ": must be published (status=1)");
            assertEquals(1, ((Number) row.get("asset_version")).intValue(),
                    name + ": factory rows are first-version heads");
            assertNull(row.get("prev_asset_id"),
                    name + ": first publish has no chain predecessor");
        }
    }

    @Test
    void payloadsFollowTheAssetRefPublishContractAndIrVerbList() {
        long factoryId = ((Number) factoryProjectRow().get("id")).longValue();
        Set<String> irVerbs = new HashSet<>(UiActionContract.IR_VERBS);
        int totalSteps = 0;
        for (String name : PACK_NAMES) {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    select kind, payload_json from tp_asset
                    where project_id = ? and name = ?
                    """, factoryId, name);
            String kind = (String) row.get("kind");
            JsonNode payload = parse(name, (String) row.get("payload_json"));
            List<JsonNode> steps;
            if ("case_fragment".equals(kind)) {
                assertTrue(payload.isArray(), name + ": case_fragment payload must be a step array");
                steps = stepsOf(payload);
            } else {
                assertTrue(payload.isObject(), name + ": action_component payload must be one step object");
                steps = List.of(payload);
            }
            assertFalse(steps.isEmpty(), name + ": step list must not be empty");
            for (JsonNode step : steps) {
                assertTrue(step.isObject(), name + ": every step must be an object");
                assertTrue(step.path("action").isTextual() && !step.path("action").asText().isBlank(),
                        name + ": every step carries a non-blank action string");
                String action = step.path("action").asText();
                assertTrue(irVerbs.contains(action),
                        name + ": verb '" + action + "' is outside the IR verb list (29 single-sourced verbs)");
                totalSteps++;
            }
            assertFalse(((String) row.get("payload_json")).contains("dragDrop"),
                    name + ": dragDrop is not part of the IR verb baseline");
        }
        assertTrue(totalSteps >= 5 * 2,
                "the five shipped components must carry a realistic step body (got " + totalSteps + " steps)");
    }

    @Test
    void credentialSurfacesStayReferenceNamesOnly() {
        long factoryId = ((Number) factoryProjectRow().get("id")).longValue();
        int credRefs = 0;
        for (String name : PACK_NAMES) {
            String payloadJson = jdbcTemplate.queryForObject("""
                    select payload_json from tp_asset
                    where project_id = ? and name = ?
                    """, String.class, factoryId, name);
            JsonNode payload = parse(name, payloadJson);
            List<JsonNode> steps = payload.isArray() ? stepsOf(payload) : List.of(payload);
            for (JsonNode step : steps) {
                JsonNode value = step.get("value");
                if (value != null && value.isTextual() && value.asText().contains("{{")) {
                    assertTrue(NAMESPACE_REF.matcher(value.asText()).matches(),
                            name + ": interpolated step value must be a whole namespace reference, got '"
                                    + value.asText() + "'");
                }
            }
            String stripped = payloadJson.replaceAll("\\{\\{(CRED|VAR|ENV|DATA|PACK):[^{}]+}}", "");
            for (String forbidden : List.of("password", "passwd", "secret", "token")) {
                assertFalse(stripped.toLowerCase().contains(forbidden),
                        name + ": credential-ish literal '" + forbidden + "' must not ship outside references");
            }
            if (payloadJson.contains("{{CRED:")) {
                credRefs++;
            }
        }
        assertTrue(credRefs >= 1,
                "the shipped pack must demonstrate the credential-reference surface at least once");
    }

    private JsonNode parse(String name, String payloadJson) {
        try {
            return MAPPER.readTree(payloadJson);
        } catch (Exception e) {
            throw new AssertionError(name + ": payload_json must be valid JSON", e);
        }
    }

    /** Element list of an array node (Jackson API level kept to the repo's pinned version). */
    private static List<JsonNode> stepsOf(JsonNode arrayNode) {
        List<JsonNode> steps = new ArrayList<>();
        arrayNode.forEach(steps::add);
        return steps;
    }
}
