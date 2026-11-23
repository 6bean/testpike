package com.sixbean.testpike.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class TlsContextResolverTest {

    private static final String FAKE_CA = "-----BEGIN CERTIFICATE-----\nMIIDRT\n-----END CERTIFICATE-----";
    private static final String FAKE_CERT = "-----BEGIN CERTIFICATE-----\nMIIDCE\n-----END CERTIFICATE-----";
    private static final String FAKE_KEY = "-----BEGIN PRIVATE KEY-----\nMIIEvQ\n-----END PRIVATE KEY-----";

    @Test
    void 模式缺失或未知_失败安全落strict() {
        assertEquals(TlsMode.STRICT, TlsContextResolver.resolve(null, null, null, null).mode());
        assertEquals(TlsMode.STRICT, TlsContextResolver.resolve("  ", null, null, null).mode());
        assertEquals(TlsMode.STRICT, TlsContextResolver.resolve("ignore_all", null, null, null).mode());
    }

    @Test
    void strict透传_不携带任何证书材料() {
        TlsPolicy p = TlsContextResolver.resolve("strict", FAKE_CA, null, null);
        assertEquals(TlsMode.STRICT, p.mode());
        assertFalse(p.ignoreVerify());
    }

    @Test
    void insecure原样透传_消费侧据此跳过校验() {
        TlsPolicy p = TlsContextResolver.resolve("insecure", null, null, null);
        assertEquals(TlsMode.INSECURE, p.mode());
        assertTrue(p.ignoreVerify());
    }

    @Test
    void custom_ca带CA_保留校验材料() {
        TlsPolicy p = TlsContextResolver.resolve("custom_ca", FAKE_CA, null, null);
        assertEquals(TlsMode.CUSTOM_CA, p.mode());
        assertEquals(FAKE_CA, p.caPem());
        assertFalse(p.ignoreVerify());
    }

    @Test
    void custom_ca缺CA_降级strict不静默放宽() {
        TlsPolicy p = TlsContextResolver.resolve("custom_ca", "  ", null, null);
        assertEquals(TlsMode.STRICT, p.mode());
        assertNull(p.caPem());
    }

    @Test
    void mTLS证书私钥成对_保留() {
        TlsPolicy p = TlsContextResolver.resolve("custom_ca", FAKE_CA, FAKE_CERT, FAKE_KEY);
        assertEquals(FAKE_CERT, p.clientCertPem());
        assertEquals(FAKE_KEY, p.clientKeyPem());
        assertTrue(p.hasClientCertPair());
    }

    @Test
    void mTLS只有证书无私钥_整体丢弃不冒充双向() {
        TlsPolicy p = TlsContextResolver.resolve("custom_ca", FAKE_CA, FAKE_CERT, null);
        assertNull(p.clientCertPem());
        assertNull(p.clientKeyPem());
        assertFalse(p.hasClientCertPair());
        assertEquals(TlsMode.CUSTOM_CA, p.mode(), "成对判定只丢弃凭据，不改传输模式");
    }

    @Test
    void mTLS只有私钥无证书_整体丢弃() {
        TlsPolicy p = TlsContextResolver.resolve("strict", null, null, FAKE_KEY);
        assertFalse(p.hasClientCertPair());
    }
}
