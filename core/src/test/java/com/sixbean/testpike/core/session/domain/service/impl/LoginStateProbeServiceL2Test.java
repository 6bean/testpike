package com.sixbean.testpike.core.session.domain.service.impl;

import com.sun.net.httpserver.HttpServer;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.ProbeResult;
import com.sixbean.testpike.core.session.domain.service.LoginStateProbeService.Verdict;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 contract for the login-state ledger "test now" door (T-tail segment,
 * material-source correction).
 *
 * <p>Red line 3 posture: real dev DB, real object storage snapshot, real
 * HTTP transport against a loopback stand-in system-under-test (the JDK
 * server IS the target here - nothing about the platform is faked). The
 * assertions pin the user promise: the trail shows address, sent material
 * (masked) and the server answer, while secret values never leak into it.
 *
 * <p>: both write channels (manual registration and capture commit)
 * store material in the vault, so the door reads the vault FIRST per kind —
 * token rides as Bearer, cookie_set/storage_state become a Cookie header via
 * the shared grammar translation. The bucket snapshot object is demoted to a
 * legacy compatibility read point, used only when the row carries no vault
 * value; a row with neither source fails with wording that names the row and
 * points at the ledger page (error guidance rules).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginStateProbeServiceL2Test {

    private static long project;
    private static String base;
    private static final String STATE_NAME = "probe_tail_state";
    private static final String TOKEN_STATE = "probe_token_state";
    private static final String STORAGE_STATE = "probe_storage_state";
    private static final String LEGACY_STATE = "probe_legacy_bucket_state";
    private static final String EMPTY_STATE = "probe_empty_state";
    private static final String JUNK_STATE = "probe_junk_state";
    private static final String OBJECT_KEY = "login-state/_probe/tail-snapshot.json";
    private static final String COOKIE_VALUE = "TOP-VALUE-3f8e21a9";
    private static final String TOKEN_VALUE = "tok-live-9f3c-22b1";
    private static final String STORAGE_COOKIE_VALUE = "SS-VALUE-77aa-c1";
    private static final String LEGACY_VALUE = "OLD-BUCKET-VALUE-1c7d";

    private static HttpServer server;
    private static final AtomicReference<String> RECEIVED_COOKIE = new AtomicReference<>("");
    private static final AtomicReference<String> RECEIVED_AUTH = new AtomicReference<>("");
    private static final AtomicReference<String> RECEIVED_METHOD = new AtomicReference<>("");
    private static final AtomicReference<String> RECEIVED_CT = new AtomicReference<>("");
    private static final AtomicReference<String> RECEIVED_BODY = new AtomicReference<>("");

    @Autowired LoginStateProbeService probeService;
    @Autowired LoginStateService loginStateService;
    @Autowired com.sixbean.testpike.core.credential.domain.service.CredentialService credentialService;
    @Autowired StorageRouter storageRouter;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    void setUp() throws Exception {
        project = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "tail-probe-" + System.nanoTime(), "tpr" + System.nanoTime());
        // the stand-in target is wired before anything references it
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/protected", exchange -> {
            RECEIVED_COOKIE.set(String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
            RECEIVED_AUTH.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = "{\"dashboard\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/bounce", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://localhost:8083/login?next=dashboard");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        // Echo target for the probe run-shape (POST/PUT + body, 11-19 face):
        // captures verb, Content-Type and the raw body for assertions.
        server.createContext("/echo", exchange -> {
            RECEIVED_METHOD.set(exchange.getRequestMethod());
            RECEIVED_CT.set(String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            RECEIVED_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"echo\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        // Legacy compatibility object: the pre-vault raw capture array shape.
        storageRouter.systemProvider().put(OBJECT_KEY,
                ("[{\"name\":\"tp_probe_sess\",\"value\":\"" + LEGACY_VALUE + "\"}]")
                        .getBytes(StandardCharsets.UTF_8), "application/json");

        // Primary row: cookie_set whose material lives ONLY in the vault, in
        // the capture-lineage entry array shape the commit channel writes.
        String entries = "[{\"kind\":\"cookie\",\"name\":\"tp_probe_sess\",\"value\":\"" + COOKIE_VALUE
                + "\",\"origin\":\"" + base + "\",\"expiresAtEpochSecond\":1893456000},"
                + "{\"kind\":\"cookie\",\"name\":\"xsrf\",\"value\":\"tail-token-value\",\"origin\":\"" + base + "\"}]";
        loginStateService.register(project, new LoginStateInput(STATE_NAME, "probe", "探针账号",
                "cookie_set", "probe_tail_cred", entries, null,
                List.of(base + "/protected"), "L2 契约行"));
        // token row rides as Authorization Bearer.
        loginStateService.register(project, new LoginStateInput(TOKEN_STATE, "probe", "探针令牌",
                "token", "probe_tail_token", TOKEN_VALUE, null,
                List.of(base + "/protected"), "L2 token 行"));
        // storage_state row keeps the Playwright document grammar in the vault.
        String playwrightDoc = "{\"cookies\":[{\"name\":\"ss_sess\",\"value\":\"" + STORAGE_COOKIE_VALUE
                + "\",\"domain\":\"127.0.0.1\",\"path\":\"/\",\"expires\":-1,\"secure\":false}],\"origins\":[]}";
        loginStateService.register(project, new LoginStateInput(STORAGE_STATE, "probe", "探针快照",
                "storage_state", "probe_tail_ss", playwrightDoc, null,
                List.of(base + "/protected"), "L2 storage_state 行"));
        // Legacy row: vault reference removed, bucket object kept (compat path).
        insertRawLedgerRow(LEGACY_STATE, "cookie_set", null, OBJECT_KEY);
        // Neither-source row.
        insertRawLedgerRow(EMPTY_STATE, "cookie_set", null, null);
        // Vault present but the content is not any known cookie grammar.
        // The 11-19 save gate refuses such a pair through the ledger doors,
        // so the probe's refusal path is exercised the way legacy data
        // actually looks: a vault row (opaque material — the vault door has
        // no kind semantics) linked directly onto the ledger row.
        long junkCred = credentialService.create(project, new com.sixbean.testpike.core.credential.api.dto.CredentialInput(
                "probe_tail_junk", "probe_tail_junk", "plain words without any pairs", "L2 坏形态行"));
        insertRawLedgerRow(JUNK_STATE, "cookie_set", junkCred, null);
    }

    /** Direct ledger insert bypassing the vault so a row can lack one. */
    private void insertRawLedgerRow(String name, String kind, Long credentialId, String objectKey) {
        jdbc.update("INSERT INTO tp_login_state (project_id, state_name, role_scope, account_label,"
                        + " kind, credential_id, state_object_key, scope_origins, source_kind, status, remark)"
                        + " VALUES (?, ?, 'probe', ?, ?, ?, ?, ?::jsonb, 1, 1, 'L2 直插行')",
                project, name, name, kind, credentialId, objectKey,
                "[\"" + base + "/protected\"]");
    }

    @AfterAll
    void cleanUp() {
        if (server != null) {
            server.stop(0);
        }
        try {
            storageRouter.systemProvider().delete(OBJECT_KEY);
        } catch (RuntimeException ignored) {
            // object already gone; the DB sweep below is the ledger cleanup
        }
        List<String> refs = jdbc.queryForList(
                "SELECT CAST(id AS VARCHAR) FROM tp_login_state WHERE project_id = ?",
                String.class, project);
        jdbc.update("DELETE FROM tp_login_state WHERE project_id = ?", project);
        jdbc.update("DELETE FROM tp_credential WHERE project_id = ?", project);
        if (!refs.isEmpty()) {
            jdbc.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'login-state-probe' AND job_ref IN ("
                    + String.join(",", refs.stream().map(r -> "?").toList()) + ")", refs.toArray());
        }
        jdbc.update("DELETE FROM tp_project WHERE id = ?", project);
    }

    // ---- run-shape face (11-19 user command: POST + body straight at the endpoint) ----

    @Test
    void postProbeDeliversBodyVerbatimAndEchoesTemplateSourceOnTrail() {
        ProbeResult result = probeService.probe(stateId(TOKEN_STATE), base + "/echo", "POST",
                "{\"page\":1,\"size\":20}");
        assertEquals(Verdict.VALID, result.verdict());
        assertEquals("POST", RECEIVED_METHOD.get());
        assertTrue(RECEIVED_CT.get().contains("application/json"),
                "body-carrying verbs ride JSON by protocol default: " + RECEIVED_CT.get());
        assertEquals("{\"page\":1,\"size\":20}", RECEIVED_BODY.get());
        String all = trailOf(result.runToken());
        assertTrue(all.contains("构造请求：POST " + base + "/echo"), "the verb rides the request line");
        assertTrue(all.contains("请求体（模板原文，0 处保险库引用）"), "echo counts references honestly");
    }

    @Test
    void bodyCredReferenceIsFilledAtSendButNeverEchoedOnTrail() {
        credentialService.create(project,
                new com.sixbean.testpike.core.credential.api.dto.CredentialInput(
                        "body 引用", "body_ref_key", "BODY-SECRET-9f3c-00aa", "L2 引用体"));
        ProbeResult result = probeService.probe(stateId(TOKEN_STATE), base + "/echo", "POST",
                "{\"token\": \"{{CRED:body_ref_key}}\"}");
        assertEquals(Verdict.VALID, result.verdict());
        assertTrue(RECEIVED_BODY.get().contains("BODY-SECRET-9f3c-00aa"),
                "the wire carries the filled plaintext (transient, in-memory assertion only)");
        String all = trailOf(result.runToken());
        assertTrue(all.contains("{{CRED:body_ref_key}}"), "the trail echoes the template source");
        assertTrue(all.contains("1 处保险库引用"), "reference count on the echo");
        assertFalse(all.contains("BODY-SECRET-9f3c-00aa"),
                "resolved plaintext must NEVER reach the event stream");
    }

    @Test
    void unknownCredKeyInBodyIsRefusedNamingTheKeyAndTheDoor() {
        ProbeResult result = probeService.probe(stateId(TOKEN_STATE), base + "/echo", "POST",
                "{\"x\": \"{{CRED:ghost_body_key}}\"}");
        assertEquals(Verdict.FAILED, result.verdict());
        String all = trailOf(result.runToken());
        assertTrue(all.contains("ghost_body_key"), "the unknown key is named: " + all);
        assertTrue(all.contains("凭据保险箱"), "the remedy door uses the on-screen page name");
    }

    @Test
    void getProbeDropsBodyWithHintInsteadOfSilentlySendingIt() {
        ProbeResult result = probeService.probe(stateId(TOKEN_STATE), base + "/echo", "GET",
                "{\"should\":\"be dropped\"}");
        assertEquals(Verdict.VALID, result.verdict());
        assertEquals("GET", RECEIVED_METHOD.get());
        assertEquals("", RECEIVED_BODY.get(), "GET carries no body (protocol semantics)");
        assertTrue(trailOf(result.runToken()).contains("GET 请求不携带请求体"),
                "the drop is stated, never silent");
    }

    @Test
    void unsupportedVerbIsRefusedNamingTheWhitelist() {
        ProbeResult result = probeService.probe(stateId(TOKEN_STATE), base + "/echo", "DELETE", null);
        assertEquals(Verdict.FAILED, result.verdict());
        String all = trailOf(result.runToken());
        assertTrue(all.contains("请求方式不支持"), all);
        assertTrue(all.contains("只接受 GET / POST / PUT"), "the whitelist is stated");
        assertTrue(all.contains("DELETE"), "the received value is named");
    }

    private long stateId(String name) {
        Long id = jdbc.queryForObject("SELECT id FROM tp_login_state WHERE project_id = ? AND state_name = ?"
                + " ORDER BY id DESC LIMIT 1", Long.class, project, name);
        assertNotNull(id);
        return id;
    }

    private long stateId() {
        return stateId(STATE_NAME);
    }

    private List<Map<String, Object>> events(String runToken) {
        return jdbc.queryForList("SELECT level, summary, detail FROM tp_ai_task_event"
                + " WHERE job_kind = 'login-state-probe' AND run_token = ? ORDER BY id", runToken);
    }

    private String trailOf(String runToken) {
        return events(runToken).stream()
                .map(r -> r.get("summary") + "\n" + r.get("detail")).reduce("", (a, b) -> a + b);
    }

    @Test
    void validSessionProbeReadsVaultMaterialTailsMaskedCookiesAndStampsVerification() {
        long id = stateId();
        ProbeResult result = probeService.probe(id, null);
        assertEquals(Verdict.VALID, result.verdict());
        assertNotNull(result.runToken());

        assertTrue(RECEIVED_COOKIE.get().contains(COOKIE_VALUE),
                "the wire must carry the vault session value (real replay, no stand-in)");

        List<Map<String, Object>> rows = events(result.runToken());
        assertTrue(rows.size() >= 5, "start/target/request/response/verdict/close lines expected, got " + rows.size());
        String all = trailOf(result.runToken());
        assertTrue(all.contains(base + "/protected"), "the probe address must be readable in the trail");
        assertTrue(all.contains("tp_probe_sess=***"), "cookies echo as name + mask");
        assertFalse(all.contains(COOKIE_VALUE), "the session value must NEVER reach the trail");
        assertFalse(all.contains("tail-token-value"));
        assertTrue(all.contains("HTTP 200"), "the server answer belongs in the trail");
        assertTrue(all.contains("dashboard"), "response body preview rides the trail");
        assertTrue(rows.get(rows.size() - 1).get("summary").toString().startsWith("探测结束"));

        String stamped = jdbc.queryForObject(
                "SELECT to_char(last_verified_at, 'YYYY') FROM tp_login_state WHERE id = ?", String.class, id);
        assertNotNull(stamped, "a VALID verdict stamps the verification clock");
    }

    @Test
    void tokenKindRowRidesAsBearerHeaderFromVault() {
        ProbeResult result = probeService.probe(stateId(TOKEN_STATE), null);
        assertEquals(Verdict.VALID, result.verdict());
        assertEquals("Bearer " + TOKEN_VALUE, RECEIVED_AUTH.get(),
                "a token row must replay as the Authorization Bearer header");
        assertFalse(trailOf(result.runToken()).contains(TOKEN_VALUE),
                "the token value must NEVER reach the trail");
    }

    @Test
    void storageStateKindRowBuildsCookieHeaderFromPlaywrightDocument() {
        ProbeResult result = probeService.probe(stateId(STORAGE_STATE), null);
        assertEquals(Verdict.VALID, result.verdict());
        assertTrue(RECEIVED_COOKIE.get().contains("ss_sess=" + STORAGE_COOKIE_VALUE),
                "the Playwright cookies array must flatten into the replayed Cookie header");
        assertFalse(trailOf(result.runToken()).contains(STORAGE_COOKIE_VALUE));
    }

    @Test
    void legacyBucketOnlyRowStillProbesThroughTheCompatibilityReadPoint() {
        ProbeResult result = probeService.probe(stateId(LEGACY_STATE), null);
        assertEquals(Verdict.VALID, result.verdict());
        assertTrue(RECEIVED_COOKIE.get().contains(LEGACY_VALUE),
                "a row without a vault reference falls back to the bucket snapshot");
        assertFalse(trailOf(result.runToken()).contains(LEGACY_VALUE));
    }

    @Test
    void neitherSourceRowFailsNamingTheRowAndTheLedgerPage() {
        ProbeResult result = probeService.probe(stateId(EMPTY_STATE), null);
        assertEquals(Verdict.FAILED, result.verdict());
        String all = trailOf(result.runToken());
        assertTrue(all.contains("没有可重放的会话材料"), "the failure names the missing material");
        assertTrue(all.contains(EMPTY_STATE), "the failure names the row the user clicked");
        assertTrue(all.contains("登录态账本"), "the failure points at the page that fixes it");
    }

    @Test
    void untranslatableVaultContentFailsHonestlyInsteadOfGuessing() {
        ProbeResult result = probeService.probe(stateId(JUNK_STATE), null);
        assertEquals(Verdict.FAILED, result.verdict());
        String all = trailOf(result.runToken());
        assertTrue(all.contains("会话材料无法解析"), "a corrupt vault value fails with the parse verdict line");
        assertTrue(all.contains(JUNK_STATE), "the remedy wording names the affected row");
    }

    @Test
    void loginBounceIsVerdictExpiredAndLeavesTheStampAlone() {
        long id = stateId();
        jdbc.update("UPDATE tp_login_state SET last_verified_at = NULL WHERE id = ?", id);
        ProbeResult result = probeService.probe(id, base + "/bounce");
        assertEquals(Verdict.EXPIRED, result.verdict());
        String all = trailOf(result.runToken());
        assertTrue(all.contains("HTTP 302"));
        assertTrue(all.toLowerCase().contains("location"), "the bounce target must be visible for analysis");
        assertNull(jdbc.queryForObject(
                "SELECT last_verified_at FROM tp_login_state WHERE id = ?", Timestamp.class, id),
                "an EXPIRED verdict must not move the verification clock");
    }

    @Test
    void unusableAddressFailsWithPointingWording() {
        ProbeResult result = probeService.probe(stateId(), "ftp://nope");
        assertEquals(Verdict.FAILED, result.verdict());
        assertTrue(trailOf(result.runToken()).contains("地址格式不可用"),
                "the failure line names the input problem");
    }

    @Test
    void unreachableTargetReportsTransportFailureHonestly() {
        ProbeResult result = probeService.probe(stateId(), "http://127.0.0.1:1/nothing");
        assertEquals(Verdict.FAILED, result.verdict());
        String all = trailOf(result.runToken());
        assertTrue(all.contains("请求未能送达"), "transport failure is classified, not swallowed");
        assertFalse(all.contains(COOKIE_VALUE), "masking holds on the failure path too");
    }

    @Test
    void missingRowPropagatesTheHonestFourOhFour() {
        com.sixbean.testpike.common.exception.ServiceException missing =
                org.junit.jupiter.api.Assertions.assertThrows(
                        com.sixbean.testpike.common.exception.ServiceException.class,
                        () -> probeService.probe(999999999L, null));
        assertTrue(missing.getMessage().contains("不存在"));
    }
}
