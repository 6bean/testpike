package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.util.ApiWsContract;
import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.core.config.domain.component.ExecPolicyReader;
import com.sixbean.testpike.core.credential.domain.component.CredPlaceholderInjector;
import com.sixbean.testpike.core.executor.domain.component.EnvVarsDecryptSupport;
import com.sixbean.testpike.core.mock.api.dto.MockServingBinding;
import com.sixbean.testpike.core.mock.domain.component.MockServingBindingResolver;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import com.sixbean.testpike.core.spec.domain.component.ContractResponseSchemaLookup;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class ApiTestEnvelopeAssembler {

    /**
 * Run anchor row (everything the assembly pass reads in one round-trip).
 *
 * @param mockSpaceId mock space declared by the case this request was
 * promoted from (0/null = unbound; only a published
 * space turns into an envelope mock key)
 * @param collectionHeadersJson collection default headers as stored jsonb
 * text ('[]' = nothing to inherit; inheritance face)
 * @param collectionAuthJson collection default auth block as stored jsonb
 * text ('{}' = nothing to inherit)
 */
    private record RunAnchor(long projectId, Long envId, long requestId, long collectionId,
                             Long mockSpaceId, String collectionHeadersJson,
                             String collectionAuthJson) {
    }

    private final NamedParameterJdbcTemplate namedJdbc;
    private final EnvVarsDecryptSupport envVarsDecryptSupport;
    private final ApiVarPoolSupport apiVarPoolSupport;
    private final EnvTlsSupport envTlsSupport;
    private final ExecPolicyReader execPolicyReader;
    private final MockServingBindingResolver mockServingBindingResolver;
    private final CredPlaceholderInjector credPlaceholderInjector;
    private final ContractResponseSchemaLookup contractResponseSchemas;
    private final ApiEffectiveDefaultsResolver effectiveDefaults;
    private final ObjectMapper objectMapper;

    public ApiTestEnvelopeAssembler(NamedParameterJdbcTemplate namedJdbc,
                                    EnvVarsDecryptSupport envVarsDecryptSupport,
                                    ApiVarPoolSupport apiVarPoolSupport,
                                    EnvTlsSupport envTlsSupport,
                                    ExecPolicyReader execPolicyReader,
                                    MockServingBindingResolver mockServingBindingResolver,
                                    CredPlaceholderInjector credPlaceholderInjector,
                                    ContractResponseSchemaLookup contractResponseSchemas,
                                    ApiEffectiveDefaultsResolver effectiveDefaults,
                                    ObjectMapper objectMapper) {
        this.namedJdbc = namedJdbc;
        this.envVarsDecryptSupport = envVarsDecryptSupport;
        this.apiVarPoolSupport = apiVarPoolSupport;
        this.envTlsSupport = envTlsSupport;
        this.execPolicyReader = execPolicyReader;
        this.mockServingBindingResolver = mockServingBindingResolver;
        this.credPlaceholderInjector = credPlaceholderInjector;
        this.contractResponseSchemas = contractResponseSchemas;
        this.effectiveDefaults = effectiveDefaults;
        this.objectMapper = objectMapper;
    }

    
    public DispatchPayload assemble(long runId) {
        List<RunAnchor> anchors = namedJdbc.query("""
                SELECT c.project_id AS projectId, r.env_id AS envId, r.request_id AS requestId,
                       c.id AS collectionId, tc.mock_space_id AS mockSpaceId,
                       c.headers_json::text AS collectionHeadersJson,
                       c.auth_json::text AS collectionAuthJson
                FROM tp_apitest_run r
                JOIN tp_apitest_request q ON q.id = r.request_id
                JOIN tp_apitest_collection c ON c.id = q.collection_id
                LEFT JOIN tp_test_case tc ON tc.id = q.promoted_case_id AND tc.deleted = 0
                WHERE r.id = :runId
                """, new MapSqlParameterSource("runId", runId),
                (rs, n) -> new RunAnchor(rs.getLong("projectId"),
                        rs.getObject("envId") == null ? null : rs.getLong("envId"),
                        rs.getLong("requestId"), rs.getLong("collectionId"),
                        rs.getObject("mockSpaceId") == null ? null : rs.getLong("mockSpaceId"),
                        rs.getString("collectionHeadersJson"), rs.getString("collectionAuthJson")));
        if (anchors.isEmpty() || anchors.get(0).envId() == null) {
            throw new IllegalStateException("运行记录缺失或未选择环境，无法装配派发信封 runId=" + runId);
        }
        RunAnchor anchor = anchors.get(0);
        Map<String, Object> req = namedJdbc.queryForMap("""
                SELECT method, url_path AS urlPath, headers_json::text AS headersJson,
                       body_text AS bodyText, auth_json::text AS authJson,
                       assertions_json::text AS assertionsJson,
                       contract_endpoint_key AS contractEndpointKey,
                       precondition_json::text AS preconditionJson,
                       loop_json::text AS loopJson,
                       protocol
                FROM tp_apitest_request WHERE id = :id
                """, new MapSqlParameterSource("id", anchor.requestId()));
        Map<String, String> collectionPool = apiVarPoolSupport.decryptStored(
                namedJdbc.queryForObject("SELECT vars_json::text FROM tp_apitest_collection "
                        + "WHERE id = :id", new MapSqlParameterSource("id", anchor.collectionId()),
                        String.class));
        List<Map<String, Object>> globalRows = namedJdbc.queryForList(
                "SELECT var_key AS varKey, var_value AS varValue FROM tp_apitest_global_var "
                        + "WHERE project_id = :pid",
                new MapSqlParameterSource("pid", anchor.projectId()));
        Map<String, String> seed = new LinkedHashMap<>();
        for (Map<String, Object> row : globalRows) {
            seed.put(String.valueOf(row.get("varKey")), apiVarPoolSupport.decryptValue(
                    row.get("varValue") == null ? "" : String.valueOf(row.get("varValue"))));
        }
        seed.putAll(collectionPool);
        Map<String, Object> env = namedJdbc.queryForMap(
                "SELECT base_url AS baseUrl, config_json::text AS configJson "
                        + "FROM tp_project_env WHERE id = :id",
                new MapSqlParameterSource("id", anchor.envId()));

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("kind", "api-test-run");
        envelope.put("runId", runId);
        envelope.put("method", String.valueOf(req.get("method")));
        envelope.put("urlPath", String.valueOf(req.get("urlPath")));
        // inheritance face: collection defaults merge under the request
        // rows here (dispatch single point) — headers per key case-insensitively
        // with the request winning same-name entries, so an empty collection
        // keeps the old envelope byte-identical.
        envelope.set("headers", mergeHeaders(
                readJson(String.valueOf(req.get("headersJson"))),
                readJson(anchor.collectionHeadersJson())));
        envelope.set("body", bodyNode(String.valueOf(req.get("bodyText"))));
        // Auth secret slots carry credential references (@cred/key whole-string or
        // embedded {{CRED:key}} inside signature templates) enforced by the save gate;
        // the vault real value is injected here at the single assembly point — the
        // executor never reverse-queries the vault (leftover literals would be sent
        // verbatim as tokens, the exact fake-green the mobile-side gate refuses).
        // Unknown/disabled keys fail closed by name (red line 4: verdicts carry zero
        // secret values); {{gen.*}}/{{ENV:}} template text passes through untouched.
        // : the block replaces as a whole — a request without its own auth
        // inherits the collection block, {"type":"none"} opts out explicitly and
        // never falls back (inheritance must have a visible off-switch).
        JsonNode effectiveAuth = resolveEffectiveAuth(
                readJson(String.valueOf(req.get("authJson"))),
                readJson(anchor.collectionAuthJson()));
        Object authTree = objectMapper.convertValue(effectiveAuth, Object.class);
        envelope.set("auth", objectMapper.valueToTree(
                credPlaceholderInjector.injectValue(anchor.projectId(), authTree)));
        envelope.set("assertions", readJson(String.valueOf(req.get("assertionsJson"))));
        // (contractSchemas trailing key): when the assertion group
        // carries a schema row in the contract dialect, pin that endpoint's
        // response schemas (newest contract snapshot, folded-key equality through
        // the spec-domain lookup — the executor never reverse-queries the DB).
        // Zero-drift: no contract-dialect row = no key, old envelope shape byte-
        // identical; an empty map emits no key either (the executor then fails
        // closed by name — an assertion without a schema must never pass silently).
        JsonNode assertionRows = envelope.path("assertions");
        if (hasContractDialectSchemaRow(assertionRows)) {
            Map<String, String> schemas = contractResponseSchemas.schemasForKey(
                    anchor.projectId(), (String) req.get("contractEndpointKey"));
            if (!schemas.isEmpty()) {
                envelope.set("contractSchemas", objectMapper.valueToTree(schemas));
            }
        }
        // orchestration keys: precondition/loop forms ride the envelope
        // so the executor decides run-vs-honest-skip and repeat policy. Empty {} =
        // no key emitted (old envelope shape stays byte-identical for legacy rows).
        putIfNotEmpty(envelope, "precondition", readJson(String.valueOf(req.get("preconditionJson"))));
        putIfNotEmpty(envelope, "loop", readJson(String.valueOf(req.get("loopJson"))));
        String baseUrl = env.get("baseUrl") == null ? "" : String.valueOf(env.get("baseUrl"));
        envelope.put("envBaseUrl", baseUrl);
        String configJson = env.get("configJson") == null ? null : String.valueOf(env.get("configJson"));
        envelope.set("envVars", objectMapper.valueToTree(
                envVarsDecryptSupport.decryptParams(configJson)));
        // Login-state consumption channel: a pool value may hold a vault reference
        // instead of a plaintext copy, so the secret lives in exactly one place.
        // Resolution happens at the same single dispatch point as the auth block,
        // through the same injector (no second parser) — the executor never
        // reverse-queries the vault and an unknown or disabled key fails closed by
        // name carrying zero secret value. Credential-free values ({{ENV:}},
        // {{gen.*}}, plain text) pass through verbatim, so a pool without references
        // keeps the dispatched envelope byte-identical.
        if (!seed.isEmpty()) {
            envelope.set("vars", objectMapper.valueToTree(
                    credPlaceholderInjector.injectValue(anchor.projectId(), seed)));
        }
        Map<String, Object> execPolicy = execPolicyReader.toClaimPayload();
        envelope.set("execPolicy", objectMapper.valueToTree(execPolicy));
        envelope.set("tlsPolicy", tlsNode(envTlsSupport.resolvePolicyFailSafe(configJson)));
        Object timeoutSeconds = execPolicy.get("timeoutSeconds");
        envelope.put("timeoutMs", (timeoutSeconds instanceof Number n ? n.intValue() : 10) * 1000);
        // 03 trailing key: a run whose promoted case declares a mock space that is
        // PUBLISHED gets mock={spaceKey, baseUrl, snapshotVersion}; unbound /
        // draft / retired / deleted-case bindings emit no key (old shape, zero
        // drift). The executor consumes it by rewriting the run's env_base —
        // the authoritative tp_project_env profile is never touched here.
        MockServingBinding binding = mockServingBindingResolver.resolve(anchor.mockSpaceId());
        if (binding != null) {
            ObjectNode mock = objectMapper.createObjectNode();
            mock.put("spaceKey", binding.spaceKey());
            mock.put("baseUrl", binding.baseUrl());
            mock.put("snapshotVersion", binding.snapshotVersion());
            envelope.set("mock", mock);
        }
        // part 2 (03 trailing key): the transport discriminator.
        // Only a ws row stamps protocol="ws" so the executor branches onto the
        // WebSocket consumption path; http, blank and any dirty legacy value
        // emit no key at all (old envelope shape stays byte-identical, the
        // executor's default HTTP branch is untouched — fail-safe toward the
        // transport that has always worked). Vocabulary reads through the
        // single-source ApiWsContract; the assembler never carries a second copy.
        String transport = ApiWsContract.normalize(
                req.get("protocol") == null ? null : String.valueOf(req.get("protocol")));
        if ("ws".equals(transport)) {
            envelope.put("protocol", transport);
        }
        try {
            return new DispatchPayload(anchor.projectId(), objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("api-test-run 信封序列化失败", e);
        }
    }

    
    public record DispatchPayload(long projectId, String envelopeJson) {
    }

    private JsonNode readJson(String raw) {
        try {
            return raw == null || raw.isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** Set an envelope key only when the node carries content ({}/null = absent). */
    private void putIfNotEmpty(ObjectNode envelope, String key, JsonNode node) {
        if (node != null && !node.isNull() && !node.isEmpty()) {
            envelope.set(key, node);
        }
    }

    /**
     * True when any assertion row is a schema assertion in the contract dialect
     * (via=contract; the no-via default is inline-only and needs no pinning).
     * Drives the contractSchemas trailing key so inline-only groups keep the old
     * envelope shape byte-for-byte.
     */
    private static boolean hasContractDialectSchemaRow(JsonNode rows) {
        if (!rows.isArray()) {
            return false;
        }
        for (JsonNode row : rows) {
            if ("schema".equals(row.path("type").asText(""))
                    && "contract".equals(row.path("via").asText(""))) {
                return true;
            }
        }
        return false;
    }

    
    private JsonNode bodyNode(String bodyText) {
        if (bodyText == null || bodyText.isBlank() || "null".equals(bodyText)) {
            return null;
        }
        try {
            return objectMapper.readTree(bodyText);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(bodyText);
        }
    }

    /**
 * Merge request and collection header rows into the envelope object form
 * : delegated to {@link ApiEffectiveDefaultsResolver} — one verdict
 * for dispatch and the editor preview alike (red line 6, zero second copy).
 */
    private JsonNode mergeHeaders(JsonNode requestStored, JsonNode collectionStored) {
        return effectiveDefaults.mergedHeadersObject(requestStored, collectionStored);
    }

    /**
 * Resolve the effective auth block : the
 * resolver also reports provenance; the envelope only consumes the block.
 */
    private JsonNode resolveEffectiveAuth(JsonNode requestAuth, JsonNode collectionAuth) {
        return effectiveDefaults.resolveAuth(requestAuth, collectionAuth).block();
    }

    
    private ObjectNode tlsNode(TlsPolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("mode", policy.mode().raw());
        if (policy.caPem() != null) {
            node.put("caPem", policy.caPem());
        }
        if (policy.hasClientCertPair()) {
            node.put("clientCertPem", policy.clientCertPem());
            node.put("clientKeyPem", policy.clientKeyPem());
        }
        return node;
    }
}
