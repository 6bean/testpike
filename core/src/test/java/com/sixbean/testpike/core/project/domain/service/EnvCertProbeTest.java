package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.util.TlsContextResolver;
import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.component.CertProbeOutcome;
import com.sixbean.testpike.core.project.domain.component.EnvCertProber;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class EnvCertProbeTest {

    private static final String KEY_ENABLED = "env.health-check.enabled";
    private static final String KEY_WARN_DAYS = "tls.cert.expire-warn-days";

    @Autowired
    private EnvHealthService envHealthService;

    @Autowired
    private EnvCertProber certProber;

    @Autowired
    private ProjectEnvService envService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private HttpsServer server;
    private int port;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type IN ('ENV_UNHEALTHY','ENV_RECOVERED','CERT_EXPIRING')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE name LIKE 'w1aenv_%'");
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id IN (SELECT id FROM tp_project WHERE code LIKE 'w1ap%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'w1ap%'");
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN (SELECT id FROM tp_user WHERE username LIKE 'w1a_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'w1a_%'");
        stopServer();
    }


    private void startHttpsServer() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/tls/selfsigned.p12")) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    
    private String fixtureCaPem() throws Exception {
        byte[] der;
        try (InputStream in = getClass().getResourceAsStream("/tls/selfsigned.pem");
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            in.transferTo(bos);
            der = bos.toByteArray();
        }
        String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    private static TlsPolicy policy(String mode, String caPem) {
        return TlsContextResolver.resolve(mode, caPem, null, null);
    }


    private long[] seedProjectEnv(String baseUrl, String configJson) {
        long ownerId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, 3, 1) RETURNING id""", Long.class,
                "w1a_owner_" + System.nanoTime() % 1000000, "w1a_owner");
        String code = "w1ap" + System.nanoTime() % 1000000;
        long projectId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES ('W1a项目', ?, 'zh-CN', 1) RETURNING id""", Long.class, code);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 2)",
                projectId, ownerId);
        long envId = envService.create(projectId, new ProjectEnvInput(
                "w1aenv_" + code, 2, baseUrl, configJson, null, null, null));
        return new long[] {projectId, envId, ownerId};
    }

    private void setConfig(String key, String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag)
                VALUES (?, ?, '安全', 'int', 0)
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value""", key, value);
        configCacheHolder.put(key, value);
    }

    private Map<String, Object> certColumns(long envId) {
        return jdbcTemplate.queryForMap("""
                SELECT cert_issuer, cert_not_after, cert_chain_complete, cert_checked_at,
                       health_status, health_msg
                  FROM tp_project_env WHERE id = ?""", envId);
    }

    private List<String> certNotifKeys(long envId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type || '|' || user_id || '|' || dedup_key || '|' || content FROM tp_notification
                 WHERE event_type = 'CERT_EXPIRING' AND dedup_key LIKE '%:' || ? || ':%'""",
                String.class, String.valueOf(envId));
    }


    
    @Test
    void strictProbeSelfSignedCollectsButFlagsChainIncomplete() throws Exception {
        startHttpsServer();
        CertProbeOutcome o = certProber.probe("https://127.0.0.1:" + port, policy("strict", null));
        assertTrue(o.collected(), "自签证书材料照采集（观测不依赖校验通过）");
        assertFalse(o.chainTrusted(), "本机不信任=链不完整");
        assertNotNull(o.tlsVersion());
        assertTrue(o.issuer().contains("selfsigned.testpike.local"), "issuer 含 CN: " + o.issuer());
        assertNotNull(o.notAfter());
    }

    
    @Test
    void customCaProbeTrustsUploadedRoot() throws Exception {
        startHttpsServer();
        TlsPolicy p = policy("custom_ca", fixtureCaPem());
        assertEquals("custom_ca", p.mode().raw(), "夹具 CA 在位=档位不降级（resolver 单源语义）");
        CertProbeOutcome o = certProber.probe("https://127.0.0.1:" + port, p);
        assertTrue(o.collected());
        assertTrue(o.chainTrusted(), "上传内部根后应可验链");
    }

    
    @Test
    void insecureProbeCollectsHonestly() throws Exception {
        startHttpsServer();
        CertProbeOutcome o = certProber.probe("https://127.0.0.1:" + port, policy("insecure", null));
        assertTrue(o.collected());
        assertFalse(o.chainTrusted());
    }

    
    @Test
    void plainHttpYieldsAbsentOutcome() {
        CertProbeOutcome o = certProber.probe("http://127.0.0.1:1/", TlsContextResolver.defaultStrict());
        assertFalse(o.collected());
        CertProbeOutcome dead = certProber.probe("https://127.0.0.1:1/", TlsContextResolver.defaultStrict());
        assertFalse(dead.collected(), "不可达=采集失败诚实 absent");
    }


    
    @Test
    void scanWritesCertObservabilityColumns() throws Exception {
        setConfig(KEY_ENABLED, "1");
        setConfig(KEY_WARN_DAYS, "30");
        startHttpsServer();
        long[] ids = seedProjectEnv("https://127.0.0.1:" + port,
                "{\"tls\":{\"mode\":\"insecure\",\"reason\":\"自签夹具联调\"}}");
        envHealthService.scanOne(ids[1]);
        Map<String, Object> row = certColumns(ids[1]);
        assertEquals(1, row.get("health_status"), "insecure 档探测应放行自签（探针消费 tls 决议）");
        assertNotNull(row.get("cert_issuer"));
        assertNotNull(row.get("cert_not_after"));
        assertEquals(0, ((Number) row.get("cert_chain_complete")).intValue(), "自签=链不完整落 0");
        assertNotNull(row.get("cert_checked_at"));
    }

    
    @Test
    void httpEnvLeavesCertColumnsNull() throws Exception {
        setConfig(KEY_ENABLED, "1");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        envHealthService.scanOne(ids[1]);
        Map<String, Object> row = certColumns(ids[1]);
        assertNull(row.get("cert_issuer"));
        assertNull(row.get("cert_checked_at"));
    }

    
    @Test
    void getHealthCarriesLastCertProbe() throws Exception {
        setConfig(KEY_ENABLED, "1");
        startHttpsServer();
        long[] ids = seedProjectEnv("https://127.0.0.1:" + port,
                "{\"tls\":{\"mode\":\"insecure\",\"reason\":\"x\"}}");
        envHealthService.scanOne(ids[1]);
        Map<String, Object> card = envHealthService.getHealth(ids[1]);
        assertNotNull(card.get("certIssuer"), "读路径带出发行者");
        assertNotNull(card.get("certCheckedAt"));
        assertEquals("insecure", card.get("tlsMode"), "档位回显（角标判定面）");
    }


    private void seedExpiringCert(long envId, String notAfterExpr) {
        jdbcTemplate.update("UPDATE tp_project_env SET cert_issuer='CN=selfsigned.testpike.local', "
                + "cert_not_after = " + notAfterExpr + ", cert_chain_complete = 1, "
                + "cert_checked_at = now() WHERE id = ?", envId);
    }

    
    @Test
    void expiringWithinWindowNotifiesOwnerOncePerDay() {
        setConfig(KEY_WARN_DAYS, "30");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        seedExpiringCert(ids[1], "now() + interval '10 days'");
        int notified = envHealthService.scanCertExpiry();
        List<String> keys = certNotifKeys(ids[1]);
        assertEquals(1, notified, "一个到期环境一个负责人=1 条");
        assertEquals(1, keys.size(), "实际计数与返回一致");
        assertTrue(keys.get(0).contains("CERT_EXPIRING|" + ids[2] + "|"), "收件人=项目负责人");
        assertTrue(keys.get(0).contains("到期") && keys.get(0).contains("剩余 10 天"),
                "人话模板: " + keys.get(0));
        envHealthService.scanCertExpiry();
        assertEquals(1, certNotifKeys(ids[1]).size(), "同日不得刷屏");
    }

    
    @Test
    void expiredCertSaysAlreadyExpired() {
        setConfig(KEY_WARN_DAYS, "30");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        seedExpiringCert(ids[1], "now() - interval '1 days'");
        envHealthService.scanCertExpiry();
        assertTrue(certNotifKeys(ids[1]).get(0).contains("已过期"),
                "已过期文案: " + certNotifKeys(ids[1]).get(0));
    }

    
    @Test
    void disabledOrBeyondWindowSilent() {
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        seedExpiringCert(ids[1], "now() + interval '100 days'");
        setConfig(KEY_WARN_DAYS, "30");
        assertEquals(0, envHealthService.scanCertExpiry(), "窗外不发");
        setConfig(KEY_WARN_DAYS, "0");
        seedExpiringCert(ids[1], "now() + interval '5 days'");
        assertEquals(0, envHealthService.scanCertExpiry(), "warn-days=0 停用");
        setConfig(KEY_WARN_DAYS, "30");
    }
}
