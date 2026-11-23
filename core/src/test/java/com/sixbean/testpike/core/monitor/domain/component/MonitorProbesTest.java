package com.sixbean.testpike.core.monitor.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInput;
import com.sixbean.testpike.core.dbconnection.domain.service.DbConnectionService;
import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class MonitorProbesTest {

    @Autowired
    private List<MonitorProbe> probeBeans;

    @Autowired
    private DbConnectionService dbConnectionService;

    @Autowired
    private com.sixbean.testpike.core.session.domain.service.LoginStateService loginStateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Map<String, MonitorProbe> probes;
    private HttpServer server;
    private int port;
    private long projectId;
    private long envId;

    private MonitorProbe probe(String type) {
        return probes.get(type);
    }

    @BeforeEach
    void setUp() throws IOException {
        cleanFixtureTables();
        probes = probeBeans.stream()
                .collect(Collectors.toMap(MonitorProbe::supportedType, Function.identity()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "pong-ok-body"));
        server.createContext("/boom", ex -> respond(ex, 500, "down"));
        server.createContext("/login", this::handleLogin);
        server.createContext("/login-extra", this::handleLoginExtra);
        server.createContext("/login-captcha", this::handleLoginExtra);
        server.createContext("/protected", this::handleGuardBearer);
        server.createContext("/protected-cookie", this::handleGuardCookie);
        server.createContext("/deny", ex -> respond(ex, 403, "denied"));
        server.createContext("/redirect-home", this::handleRedirect);
        server.createContext("/redirect-to-401", ex -> {
            ex.getResponseHeaders().add("Location", url("/deny"));
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();

        String tag = "t274probe" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                tag, tag.substring(tag.length() - 8).toUpperCase());
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE name = ?", Long.class, tag);
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url, config_json) "
                + "VALUES (?, '主环境', 1, ?, "
                + "'{\"params\":{\"mon-login\":\"u1:p@ss-w3-probe\",\"mon-plain\":\"u1:p@ss-w3-probe\",\"mon-captcha\":\"123456\"}}')",
                projectId, "http://127.0.0.1:" + port + "/ok");
        envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? LIMIT 1", Long.class, projectId);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        cleanFixtureTables();
    }

    private void cleanFixtureTables() {
        jdbcTemplate.update("DELETE FROM tp_login_state WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274probe%')");
        jdbcTemplate.update("DELETE FROM tp_credential WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274probe%')");
        jdbcTemplate.update("DELETE FROM tp_monitor_check_log WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274probe%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274probe%')");
        jdbcTemplate.update("DELETE FROM tp_db_connection WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274probe%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274probe%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274probe%'");
    }

    private void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    
    private void handleLogin(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean ok = body.contains("\"username\":\"u1\"") && body.contains("\"password\":\"p@ss-w3-probe\"");
        respond(ex, ok ? 200 : 401, ok ? "logged-in" : "bad-credentials");
    }

    
    private void handleLoginExtra(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean ok = body.contains("\"username\":\"u1\"") && body.contains("\"password\":\"p@ss-w3-probe\"")
                && body.contains("\"tenant\":\"acme\"") && body.contains("\"captcha\":\"123456\"");
        respond(ex, ok ? 200 : 401, ok ? "logged-in" : "extra-fields-missing");
    }

    
    private void handleGuardBearer(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        boolean ok = "Bearer tok-9f3-value".equals(auth) || "Bearer post-captcha-token".equals(auth);
        respond(ex, ok ? 200 : 401, ok ? "alive" : "denied");
    }

    private void handleGuardCookie(HttpExchange ex) throws IOException {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        respond(ex, "SESSIONID=v1; sid=v2".equals(cookie) ? 200 : 401, "checked");
    }

    private void handleRedirect(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Location", url("/protected"));
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private MonitorTarget target(String type, String probeConfigJson) {
        MonitorTarget t = new MonitorTarget();
        t.setProjectId(projectId);
        t.setEnvId(envId);
        t.setName("探针夹具");
        t.setTargetType(type);
        t.setProbeConfig(probeConfigJson);
        return t;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    // ---------- HTTP / ONLINE ----------

    @Test
    void httpProbePasses200AndBodyExpectation() {
        MonitorProbeResult r = probe("HTTP").probe(
                target("HTTP", "{\"url\":\"" + url("/ok") + "\",\"expect\":{\"body\":\"pong-ok\"}}"));
        assertTrue(r.success(), "200+体含预期片段应过: " + r.cause());
        assertNotNull(r.latencyMs());
        assertTrue(r.latencyMs() >= 0, "延迟应为 nanoTime 计时的非负毫秒");
    }

    @Test
    void httpProbeFailsWhenBodyMismatch() {
        MonitorProbeResult r = probe("HTTP").probe(
                target("HTTP", "{\"url\":\"" + url("/ok") + "\",\"expect\":{\"body\":\"不存在的片段\"}}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("不存在的片段"), "人话 cause 应含预期片段回显: " + r.cause());
    }

    @Test
    void httpProbeFailsOnNon2xxAndUnreachable() {
        MonitorProbeResult boom = probe("HTTP").probe(
                target("HTTP", "{\"url\":\"" + url("/boom") + "\"}"));
        assertFalse(boom.success());
        assertTrue(boom.cause().contains("500"), "非 2xx 应报状态码: " + boom.cause());

        MonitorProbeResult dead = probe("HTTP").probe(
                target("HTTP", "{\"url\":\"http://127.0.0.1:1/none\"}"));
        assertFalse(dead.success());
        assertNotNull(dead.cause());
    }

    @Test
    void httpProbeRejectsMissingUrlWithHumanCause() {
        MonitorProbeResult r = probe("HTTP").probe(target("HTTP", "{}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("url"), "缺 url 应人话指认配置项: " + r.cause());
    }

    @Test
    void onlineProbeSharesHttpExchangeSurface() {
        MonitorProbeResult ok = probe("ONLINE").probe(
                target("ONLINE", "{\"url\":\"" + url("/ok") + "\",\"expect\":{\"body\":\"pong-ok\"}}"));
        assertTrue(ok.success(), "在线探测与 HTTP 同判定面: " + ok.cause());
        MonitorProbeResult bad = probe("ONLINE").probe(
                target("ONLINE", "{\"url\":\"" + url("/boom") + "\"}"));
        assertFalse(bad.success());
    }

    // ---------- LOGIN ----------

    @Test
    void loginProbePostsCredentialFromEnvParams() {
        MonitorProbeResult r = probe("LOGIN").probe(
                target("LOGIN", "{\"url\":\"" + url("/login") + "\",\"credential\":\"@cred/mon-login\"}"));
        assertTrue(r.success(), "@cred 键→params 解密→POST 2xx 应过: " + r.cause());
    }

    @Test
    void loginProbeMissingCredentialKeyFailsWithoutLeak() {
        MonitorProbeResult r = probe("LOGIN").probe(
                target("LOGIN", "{\"url\":\"" + url("/login") + "\",\"credential\":\"@cred/no-such-key\"}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("no-such-key"), "人话应指认缺失键: " + r.cause());
        assertFalse(r.cause().contains("p@ss"), "cause 禁泄漏口令明文");
    }

    @Test
    void loginProbeBadCredentialsFailsHumanReadable() {
        jdbcTemplate.update("UPDATE tp_project_env SET config_json = "
                + "'{\"params\":{\"mon-login\":\"u1:wrong-pass\"}}' WHERE id = ?", envId);
        MonitorProbeResult r = probe("LOGIN").probe(
                target("LOGIN", "{\"url\":\"" + url("/login") + "\",\"credential\":\"@cred/mon-login\"}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("401"), "非 2xx 应报状态码: " + r.cause());
        assertFalse(r.cause().contains("wrong-pass"), "cause 禁泄漏口令明文");
    }

    // ---------- DB ----------

    @Test
    void dbProbeRealSelect1ThroughConnectionService() {
        long connId = dbConnectionService.create(projectId, new DbConnectionInput(envId,
                "探针平台库", "pg", System.getenv("DB_URL"),
                System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"), true, 2));
        MonitorProbeResult r = probe("DB").probe(
                target("DB", "{\"connectionId\":" + connId + "}"));
        assertTrue(r.success(), "真库 SELECT 1 应过: " + r.cause());
        assertTrue(r.latencyMs() >= 0);
    }

    @Test
    void dbProbeUnknownConnectionFailsHuman() {
        MonitorProbeResult r = probe("DB").probe(target("DB", "{\"connectionId\":999999999}"));
        assertFalse(r.success());
        assertFalse(r.cause().contains("at com."), "禁堆栈外泄: " + r.cause());
    }

    @Test
    void dbProbeMissingConnectionIdRejected() {
        MonitorProbeResult r = probe("DB").probe(target("DB", "{}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("connectionId"), "缺配置项人话指认: " + r.cause());
    }


    private void setCertObservation(Timestamp notAfter, int chainComplete, Timestamp checkedAt) {
        jdbcTemplate.update("UPDATE tp_project_env SET cert_not_after = ?, "
                + "cert_chain_complete = ?::smallint, cert_checked_at = ? WHERE id = ?",
                notAfter, chainComplete, checkedAt, envId);
    }

    @Test
    void certProbeOkOutsideExpireWindow() {
        setCertObservation(Timestamp.valueOf(LocalDateTime.now().plusDays(365)), 1,
                Timestamp.valueOf(LocalDateTime.now()));
        MonitorProbeResult r = probe("CERT").probe(target("CERT", "{}"));
        assertTrue(r.success(), "远离到期窗应过: " + r.cause());
    }

    @Test
    void certProbeFailsInsideExpireWindow() {
        setCertObservation(Timestamp.valueOf(LocalDateTime.now().plusDays(3)), 1,
                Timestamp.valueOf(LocalDateTime.now()));
        MonitorProbeResult r = probe("CERT").probe(target("CERT", "{}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("到期"), "人话应指认到期窗: " + r.cause());
    }

    @Test
    void certProbeFailsOnUntrustedChain() {
        setCertObservation(Timestamp.valueOf(LocalDateTime.now().plusDays(365)), 0,
                Timestamp.valueOf(LocalDateTime.now()));
        MonitorProbeResult r = probe("CERT").probe(target("CERT", "{}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("证书链"), "人话应指认证书链: " + r.cause());
    }

    @Test
    void certProbeNoObservationIsHonestFailNotDeferred() {
        MonitorProbeResult r = probe("CERT").probe(target("CERT", "{}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("证书"), "无观测=诚实 fail 人话: " + r.cause());
    }

    @Test
    void certProbeStaleObservationPromptsReprobe() {
        setCertObservation(Timestamp.valueOf(LocalDateTime.now().plusDays(365)), 1,
                Timestamp.valueOf(LocalDateTime.now().minusDays(7)));
        MonitorProbeResult r = probe("CERT").probe(target("CERT", "{}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("重探") || r.cause().contains("健康检查"),
                "观测过旧应提示重探: " + r.cause());
    }


    @Test
    void fiveProbesRegisteredWithUniqueTypes() {
        assertEquals(5, probeBeans.size(), "五类探针应全部注册为 Bean 且类型不重");
        assertEquals(5, probes.size(), "supportedType 键须互异");
        assertTrue(probes.keySet().containsAll(List.of("HTTP", "LOGIN", "ONLINE", "DB", "CERT")),
                "五类键齐: " + probes.keySet());
    }


    @Test
    void loginExtraFieldsCarriedFromEnvParamAndLiteral() {
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"url\":\"" + url("/login-extra") + "\",\"credential\":\"@cred/mon-login\","
                        + "\"extraFields\":{\"tenant\":\"acme\",\"captcha\":\"@env/mon-captcha\"}}"));
        assertTrue(r.success(), "附加字段齐备应过: " + r.cause());
    }

    @Test
    void loginExtraFieldMissingEnvParamFailsPointingKey() {
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"url\":\"" + url("/login-extra") + "\",\"credential\":\"@cred/mon-login\","
                        + "\"extraFields\":{\"tenant\":\"acme\",\"captcha\":\"@env/no-such\"}}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("no-such"), "应点名缺失的键名: " + r.cause());
    }

    @Test
    void stateReadTokenRowCarriesBearerAndPasses() {
        registerLedgerState("m1-token", "token", "tok-9f3-value");
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"m1-token\",\"checkUrl\":\""
                        + url("/protected") + "\"}"));
        assertTrue(r.success(), "token 形态直读应携 Bearer 过: " + r.cause());
    }

    @Test
    void stateReadCookieSetRowFlattensToCookieHeader() {
        // Capture-shape entries (kind + origin) so the save-door consistency
        // gate can replay them; the probe still flattens name=value pairs.
        registerLedgerState("m1-cookie", "cookie_set",
                "[{\"kind\":\"cookie\",\"name\":\"SESSIONID\",\"value\":\"v1\","
                        + "\"origin\":\"http://127.0.0.1:" + port + "\"},"
                        + "{\"kind\":\"cookie\",\"name\":\"sid\",\"value\":\"v2\","
                        + "\"origin\":\"http://127.0.0.1:" + port + "\"}]");
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"m1-cookie\",\"checkUrl\":\""
                        + url("/protected-cookie") + "\"}"));
        assertTrue(r.success(), "cookie_set 形态应摊平为 Cookie 头: " + r.cause());
    }

    @Test
    void stateReadAccepts302AsAliveWithoutFollowing() {
        registerLedgerState("m1-token", "token", "tok-9f3-value");
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"m1-token\",\"checkUrl\":\""
                        + url("/redirect-home") + "\"}"));
        assertTrue(r.success(), "302 按设计件判活: " + r.cause());
        MonitorProbeResult followed = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"m1-token\",\"checkUrl\":\""
                        + url("/redirect-to-401") + "\"}"));
        assertTrue(followed.success(), "302 不跟随=裸状态码判活: " + followed.cause());
    }

    @Test
    void stateReadProtectedRejectFailsWithoutLeakingSecret() {
        registerLedgerState("m1-token", "token", "tok-9f3-value");
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"m1-token\",\"checkUrl\":\""
                        + url("/deny") + "\"}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("403"), "应报状态码: " + r.cause());
        assertFalse(r.cause().contains("tok-9f3"), "脱敏钉：值禁进留痕");
    }

    @Test
    void stateReadUnknownStatePointsToLedger() {
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"没有这条\",\"checkUrl\":\""
                        + url("/protected") + "\"}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("登录态账本"), "指路账本页: " + r.cause());
    }

    @Test
    void stateReadStorageSnapshotRowHonestlyRefused() {
        registerLedgerState("m1-snap", "storage_state",
                "[{\"kind\":\"cookie\",\"name\":\"S\",\"value\":\"v\","
                        + "\"origin\":\"http://127.0.0.1:" + port + "\"}]");
        MonitorProbeResult r = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"m1-snap\",\"checkUrl\":\""
                        + url("/protected") + "\"}"));
        assertFalse(r.success());
        assertTrue(r.cause().contains("快照") || r.cause().contains("storage_state"),
                "快照形态诚实拒并指路: " + r.cause());
    }

    @Test
    void captchaTargetStateReadPassesWhileLoginPostHonestlyRefuses() {
        String cfg = "{\"url\":\"" + url("/login-captcha") + "\",\"credential\":\"@cred/mon-login\"}";
        assertFalse(probe("LOGIN").probe(target("LOGIN", cfg)).success(),
                "登录 POST 形态打验证码端点应诚实拒");
        registerLedgerState("m1-captcha-session", "token", "post-captcha-token");
        MonitorProbeResult ok = probe("LOGIN").probe(target("LOGIN",
                "{\"mode\":\"state-read\",\"stateName\":\"m1-captcha-session\",\"checkUrl\":\""
                        + url("/protected") + "\"}"));
        assertTrue(ok.success(), "直读形态应过: " + ok.cause());
    }

    /** Ledger fixture: same vault-key ASCII-strip discipline as SessionStateBrowserSnapshotTest. */
    private void registerLedgerState(String stateName, String kind, String secret) {
        String credKey = "m1" + stateName.replaceAll("[^A-Za-z0-9_]", "");
        loginStateService.register(projectId, new com.sixbean.testpike.core.session.api.dto.LoginStateInput(
                stateName, null, null, kind, credKey, secret, null,
                List.of("http://127.0.0.1:" + port), "m1 probe fixture"));
    }
}
