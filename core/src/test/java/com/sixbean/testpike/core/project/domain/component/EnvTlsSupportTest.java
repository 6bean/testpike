package com.sixbean.testpike.core.project.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class EnvTlsSupportTest {

    private static final String CA_PEM = "-----BEGIN CERTIFICATE-----\nMIIca\n-----END CERTIFICATE-----";
    private static final String CERT_PEM = "-----BEGIN CERTIFICATE-----\nMIIcr\n-----END CERTIFICATE-----";
    private static final String KEY_PEM = "-----BEGIN PRIVATE KEY-----\nMIIkr\n-----END PRIVATE KEY-----";

    private EnvTlsSupport support;
    private final ObjectMapper objectMapper = new ObjectMapper();

    
    private static ConfigCipher cipher(boolean enabled) {
        return new ConfigCipher(enabled ? "00112233445566778899aabbccddeeff" : "");
    }

    @BeforeEach
    void setUp() {
        support = new EnvTlsSupport(cipher(true), objectMapper);
    }


    @Test
    void 缺段与坏段_读为strict失败安全() throws Exception {
        assertEquals("strict", support.readMode(null));
        assertEquals("strict", support.readMode("{\"loginUrl\":\"/login\"}"));
        assertEquals("strict", support.readMode("{\"tls\":\"nonsense\"}"));
        assertEquals("strict", support.readMode("{\"tls\":{\"mode\":\"weird\"}}"));
    }

    @Test
    void 三档值域读回正确() throws Exception {
        assertEquals("custom_ca", support.readMode("{\"tls\":{\"mode\":\"custom_ca\"}}"));
        assertEquals("insecure", support.readMode("{\"tls\":{\"mode\":\"INSECURE\"}}"), "大小写归一");
    }


    @Test
    void 生产环境选insecure_保存即拦400人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.assertModeAllowed(4, "insecure"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("生产环境"), "人话点名生产，当前=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("跳过证书校验") || ex.getMessage().contains("insecure"),
                "讲清禁的是什么，当前=" + ex.getMessage());
    }

    @Test
    void 非生产段_insecure与其余档放行() {
        support.assertModeAllowed(2, "insecure");
        support.assertModeAllowed(1, "strict");
        support.assertModeAllowed(3, "custom_ca");
        support.assertModeAllowed(4, "strict");
        support.assertModeAllowed(4, "custom_ca");
    }


    @Test
    void merge_ca与私钥落ENC密文_非tls键保留() throws Exception {
        String merged = support.mergeTls("{\"params\":{\"A\":\"x\"}}", "custom_ca", CA_PEM, CERT_PEM, KEY_PEM, null);
        JsonNode root = objectMapper.readTree(merged);
        JsonNode tls = root.path("tls");
        assertEquals("custom_ca", tls.path("mode").asText());
        for (String field : new String[]{"ca_pem", "client_cert_pem", "client_key_pem"}) {
            assertTrue(tls.path(field).asText().startsWith(EnvTlsSupport.ENC_PREFIX),
                    field + " 须 ENC: 密文形态");
            assertTrue(!merged.contains("MIIca") && !merged.contains("MIIcr") && !merged.contains("MIIkr"),
                    "DB 侧零 PEM 明文（红线：凭据禁内联入库）");
        }
        assertTrue(root.path("params").has("A"), "非 tls 段键原样保留");
    }

    @Test
    void merge_密钥缺失保存凭据_failclosed400人话() {
        EnvTlsSupport noKey = new EnvTlsSupport(cipher(false), objectMapper);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> noKey.mergeTls(null, "custom_ca", CA_PEM, null, null, null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("CONFIG_AES_KEY"), "给键名指引，当前=" + ex.getMessage());
    }

    @Test
    void merge_打码回传未改动_保留原密文() throws Exception {
        String stored = support.mergeTls(null, "custom_ca", CA_PEM, null, null, null);
        String again = support.mergeTls(stored, "custom_ca", ConfigCipher.MASK, null, null, null);
        assertEquals(objectMapper.readTree(stored).path("tls").path("ca_pem").asText(),
                objectMapper.readTree(again).path("tls").path("ca_pem").asText(),
                "打码占位回传=保留原密文不覆盖（K8 编辑面同构）");
    }

    @Test
    void merge_伪装ENC前缀提交_400拒() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeTls(null, "custom_ca", "ENC:fake", null, null, null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void merge_mode留空_只清凭据材料_档位与理由不动() throws Exception {
        String stored = support.mergeTls(null, "custom_ca", CA_PEM, null, null, "内网根证书轮换期");
        String cleared = support.mergeTls(stored, "", null, null, null, null);
        JsonNode tls = objectMapper.readTree(cleared).path("tls");
        assertEquals("custom_ca", tls.path("mode").asText(), "留空不改档（清材料语义）");
        assertEquals("内网根证书轮换期", tls.path("reason").asText(), "理由保留");
        assertTrue(tls.path("ca_pem").asText("").isEmpty(), "材料被清");
    }

    @Test
    void merge_未知档名_400点名三档() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.mergeTls(null, "yolo", null, null, null, null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("strict"), "报出合法值域，当前=" + ex.getMessage());
    }


    @Test
    void 裸configJson路径改tls段_守卫拒400() {
        String stored = "{\"tls\":{\"mode\":\"strict\"},\"loginUrl\":\"/login\"}";
        ServiceException ex = assertThrows(ServiceException.class,
                () -> support.assertTlsSectionUnchanged(stored, "{\"tls\":{\"mode\":\"insecure\"},\"loginUrl\":\"/login\"}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        support.assertTlsSectionUnchanged(stored, stored);
        support.assertTlsSectionUnchanged("{\"loginUrl\":\"/login\"}", "{\"loginUrl\":\"/x\"}");
    }


    @Test
    void 读取视图_材料打码_档与理由明文可见() throws Exception {
        String stored = support.mergeTls(null, "custom_ca", CA_PEM, CERT_PEM, KEY_PEM, "轮换窗口");
        EnvTlsView view = support.readView(stored);
        assertEquals("custom_ca", view.mode());
        assertEquals("轮换窗口", view.reason());
        assertEquals(ConfigCipher.MASK, view.caPem());
        assertEquals(ConfigCipher.MASK, view.clientCertPem());
        assertEquals(ConfigCipher.MASK, view.clientKeyPem());
        assertTrue(view.hasCa(), "角标/锁图标判定面");
    }

    @Test
    void 无档案段_视图落strict默认无材料() throws Exception {
        EnvTlsView view = support.readView("{\"x\":1}");
        assertEquals("strict", view.mode());
        assertNull(view.caPem());
        assertTrue(!view.hasCa() && !view.hasClientCert());
    }
}
