package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunService;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatch-time credential resolution inside the api-test-run envelope (W1c segment C).
 * <p>The save gate (AuthFormContract) only checks the shape and forces secret
 * slots to carry a credential reference; the vault real value must be injected
 * here, at the single core assembly point — the executor never reverse-queries
 * the vault (same posture as the mobile-side {{CRED: leftover gate}). Two
 * reference dialects resolve to the same vault lookup: the whole-string
 * {@code @cred/key} form (monitor LOGIN precedent) and embedded
 * {@code {{CRED:key}}} placeholders (docs/04 rule 6b), so the save gate and the
 * executor stay one dialect with no second parser.
 * <p>Non-credential template text ({{gen.*}}, {{ENV:...}}) must survive
 * untouched — those belong to the executor's single resolvers; server must not
 * jump the queue. Unknown or disabled vault keys fail closed at dispatch with
 * the key name and zero secret value (red line 4).
 * <p>Real dev database, zero mocks; fixture-owned project cleaned in finally.
 * Secret-shaped literals are concatenated to keep the snapshot masking filter
 * from corrupting this source file.
 */
@SpringBootTest(classes = CoreApplication.class)
class ApiTestEnvelopeAuthResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Credential-shaped literals assembled from constants (masking-filter defence,
    // same posture as AuthFormContractTest).
    private static final String K_PASSWORD = "pass" + "word";
    private static final String SECRET_BEARER = "Br" + "9-xRealValue";
    private static final String SECRET_SK = "Sk" + "7-xRealValue";
    // Vault value referenced from the variable pool (same masking-filter defence).
    private static final String SECRET_VAR = "Vr" + "3-poolSecret";
    // {{CRED:key}} delimiters split into constants so the snapshot masking filter
    // cannot rewrite this source file.
    private static final String CRED_OPEN = "{{" + "CRED:";
    private static final String CRED_CLOSE = "}}";

    @Autowired
    private ApiRunService apiRunService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bearer_atcred_reference_resolves_to_vault_secret_at_dispatch() throws Exception {
        Fixture f = createFixture("bar");
        try {
            credentialService.create(f.projectId(),
                    new CredentialInput("令牌", "tk", SECRET_BEARER, null));
            long requestId = newRequest(f, "{\"type\":\"bearer\",\"token\":\"@cred/tk\"}");

            apiRunService.create(1L, new RunCreateInput(requestId, f.envId()));
            JsonNode env = latestEnvelope(f.projectId());

            assertEquals(SECRET_BEARER, env.path("auth").path("token").asText(),
                    "@cred/ whole-string reference must be replaced by the vault "
                            + "value at the assembly point, got: " + env.path("auth"));
        } finally {
            cleanup(f);
        }
    }

    @Test
    void signature_rows_resolve_embedded_cred_but_keep_gen_templates() throws Exception {
        Fixture f = createFixture("sig");
        try {
            credentialService.create(f.projectId(),
                    new CredentialInput("密钥", "sk", SECRET_SK, null));
            String auth = "{\"type\":\"signature\",\"assemblies\":["
                    + "{\"into\":\"header\",\"name\":\"X-Sign\","
                    + "\"value\":\"${gen.hmacSha256(text=abc,key={{CRED:sk}})}\"},"
                    + "{\"into\":\"query\",\"name\":\"ts\","
                    + "\"value\":\"${gen.timestamp()}\"}]}";
            long requestId = newRequest(f, auth);

            apiRunService.create(1L, new RunCreateInput(requestId, f.envId()));
            JsonNode env = latestEnvelope(f.projectId());
            JsonNode rows = env.path("auth").path("assemblies");

            assertEquals(2, rows.size());
            assertEquals("${gen.hmacSha256(text=abc,key=" + SECRET_SK + ")}",
                    rows.get(0).path("value").asText(),
                    "embedded {{CRED:}} inside a genkit template resolves, the "
                            + "canonical ${gen.*} wrapper stays for the executor single parser");
            assertEquals("${gen.timestamp()}", rows.get(1).path("value").asText(),
                    "credential-free template text must pass through untouched");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void basic_and_header_and_apikey_secret_slots_all_resolve() throws Exception {
        Fixture f = createFixture("multi");
        try {
            credentialService.create(f.projectId(),
                    new CredentialInput("口令", "pw", SECRET_BEARER, null));
            long req1 = newRequest(f, "{\"type\":\"basic\",\"username\":\"bob\",\""
                    + K_PASSWORD + "\":\"@cred/pw\"}");
            apiRunService.create(1L, new RunCreateInput(req1, f.envId()));
            JsonNode env1 = latestEnvelope(f.projectId());
            assertEquals(SECRET_BEARER, env1.path("auth").path(K_PASSWORD).asText());

            long req2 = newRequest(f,
                    "{\"type\":\"api-key\",\"name\":\"apiKey\",\"value\":\"@cred/pw\","
                            + "\"placement\":\"query\"}");
            apiRunService.create(1L, new RunCreateInput(req2, f.envId()));
            JsonNode env2 = latestEnvelope(f.projectId());
            assertEquals(SECRET_BEARER, env2.path("auth").path("value").asText());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void unknown_vault_key_fails_closed_naming_key_without_secret() {
        Fixture f = createFixture("miss");
        try {
            long requestId = newRequest(f, "{\"type\":\"bearer\",\"token\":\"@cred/ghost_key\"}");
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> apiRunService.create(1L, new RunCreateInput(requestId, f.envId())));
            assertTrue(ex.getMessage().contains("ghost_key"),
                    "verdict must name the missing key, got: " + ex.getMessage());
        } finally {
            cleanup(f);
        }
    }

    @Test
    void empty_auth_shape_stays_byte_identical_zero_drift() throws Exception {
        Fixture f = createFixture("none");
        try {
            long requestId = newRequest(f, "{}");
            apiRunService.create(1L, new RunCreateInput(requestId, f.envId()));
            JsonNode env = latestEnvelope(f.projectId());
            assertTrue(env.path("auth").isObject() && env.path("auth").isEmpty(),
                    "no-auth requests keep the old envelope shape, got: " + env.path("auth"));
            // headers templates (ENV namespace = executor env_resolver domain) never touched
            assertFalse(env.toString().contains("@cred/"),
                    "no leftover @cred/ literal may reach the executor");
        } finally {
            cleanup(f);
        }
    }

    // ---- variable pool credential references (login-state consumption channel) --
    // A project-level global variable or a collection pool entry may hold a vault
    // reference instead of a plaintext copy of the secret, so the secret lives in
    // exactly one place. Resolution runs at the same single dispatch point as the
    // auth block through the same injector — there is no second parser, and the
    // executor still never reverse-queries the vault.

    @Test
    void global_var_credential_reference_resolves_to_vault_value_at_dispatch() throws Exception {
        Fixture f = createFixture("varg");
        try {
            credentialService.create(f.projectId(),
                    new CredentialInput("令牌", "vargTk", SECRET_VAR, null));
            jdbc.update("INSERT INTO tp_apitest_global_var (project_id, var_key, var_value) "
                            + "VALUES (?, ?, ?)",
                    f.projectId(), "sessionToken", CRED_OPEN + "vargTk" + CRED_CLOSE);

            apiRunService.create(1L, new RunCreateInput(newRequest(f, "{}"), f.envId()));
            JsonNode env = latestEnvelope(f.projectId());

            assertEquals(SECRET_VAR, env.path("vars").path("sessionToken").asText(),
                    "a project-level variable holding a vault reference must reach the executor "
                            + "with the vault value, got: " + env.path("vars"));
            assertFalse(env.toString().contains("{{CRED" + ":vargTk"),
                    "no unresolved credential reference may leave the assembly point");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void collection_pool_reference_resolves_while_plain_values_stay_untouched() throws Exception {
        Fixture f = createFixture("varc");
        try {
            credentialService.create(f.projectId(),
                    new CredentialInput("令牌", "varcTk", SECRET_VAR, null));
            jdbc.update("UPDATE tp_apitest_collection SET vars_json = ?::jsonb WHERE id = ?",
                    "{\"token\":\"" + CRED_OPEN + "varcTk" + CRED_CLOSE
                            + "\",\"region\":\"cn-east\",\"host\":\"{{ENV:HOST}}\"}",
                    f.collectionId());

            apiRunService.create(1L, new RunCreateInput(newRequest(f, "{}"), f.envId()));
            JsonNode vars = latestEnvelope(f.projectId()).path("vars");

            assertEquals(SECRET_VAR, vars.path("token").asText(),
                    "collection pool entries resolve through the same single point");
            assertEquals("cn-east", vars.path("region").asText(),
                    "credential-free values must pass through verbatim (zero drift)");
            assertEquals("{{ENV:HOST}}", vars.path("host").asText(),
                    "the ENV namespace belongs to the executor resolver — the server must not "
                            + "jump the queue");
        } finally {
            cleanup(f);
        }
    }

    @Test
    void unknown_var_credential_reference_fails_closed_naming_key() {
        Fixture f = createFixture("varmiss");
        try {
            jdbc.update("INSERT INTO tp_apitest_global_var (project_id, var_key, var_value) "
                            + "VALUES (?, ?, ?)",
                    f.projectId(), "sessionToken", CRED_OPEN + "ghostTk" + CRED_CLOSE);
            long requestId = newRequest(f, "{}");
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> apiRunService.create(1L, new RunCreateInput(requestId, f.envId())));
            assertTrue(ex.getMessage().contains("ghostTk"),
                    "the verdict must name the missing key, got: " + ex.getMessage());
            assertFalse(ex.getMessage().contains(SECRET_VAR),
                    "a rejected dispatch may never carry a secret value");
        } finally {
            cleanup(f);
        }
    }

    // ---- fixtures (same posture as ApiTestEnvelopeMockKeyTest) --------------

    private record Fixture(long projectId, long envId, long collectionId) {
    }

    private Fixture createFixture(String tag) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String code = "c1284" + tag + suffix;
        jdbc.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "t372auth-" + code, code);
        long projectId = jdbc.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        long envId = jdbc.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url, "
                        + "config_json) VALUES (?, ?, ?, '{}'::jsonb) RETURNING id",
                Long.class, projectId, "env-" + code, "http://auth-" + projectId + ".test");
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "认证域-" + code, null));
        return new Fixture(projectId, envId, root.id());
    }

    private long newRequest(Fixture f, String authJson) {
        ApiRequestInfo req = apiRequestService.create(1L, new RequestSaveInput(
                f.collectionId(), "带认证请求", "GET", "/api/v1/orders",
                "[{\"name\":\"X-Env\",\"value\":\"{{ENV:HOST}}\"}]", null,
                authJson, "[{\"type\":\"status\",\"expect\":200}]"));
        return req.id();
    }

    private JsonNode latestEnvelope(long projectId) throws Exception {
        String actionJson = jdbc.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, projectId);
        return MAPPER.readTree(actionJson);
    }

    private void cleanup(Fixture f) {
        jdbc.update("DELETE FROM tp_exec_task WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_credential WHERE project_id = ?", f.projectId());
        // Variable-pool rows carry a real FK to tp_project, so they must leave before
        // the project row does (fixture-owned cleanup order, no cascades relied on).
        jdbc.update("DELETE FROM tp_apitest_global_var WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_apitest_run WHERE request_id IN "
                + "(SELECT id FROM tp_apitest_request WHERE collection_id = ?)", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_request WHERE collection_id = ?", f.collectionId());
        jdbc.update("DELETE FROM tp_apitest_collection WHERE id = ?", f.collectionId());
        jdbc.update("DELETE FROM tp_project_env WHERE project_id = ?", f.projectId());
        jdbc.update("DELETE FROM tp_project WHERE id = ?", f.projectId());
    }
}
