package com.sixbean.testpike.admin.project.api.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class EnvHealthViewTlsMirrorTest {

    
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void 核心载荷五观测字段全镜像() throws Exception {
        String coreJson = """
                {"status":"healthy","checkedAt":"2026-09-28 07:00:00","msg":"",
                 "tlsMode":"custom_ca","certIssuer":"CN=selfsigned.testpike.local",
                 "certNotAfter":"2036-09-25","certChainComplete":true,
                 "certCheckedAt":"2026-09-28 07:00:01"}
                """;
        EnvHealthView view = mapper.readValue(coreJson, EnvHealthView.class);
        assertEquals("healthy", view.status());
        assertEquals("custom_ca", view.tlsMode());
        assertEquals("CN=selfsigned.testpike.local", view.certIssuer());
        assertEquals("2036-09-25", view.certNotAfter());
        assertEquals(Boolean.TRUE, view.certChainComplete());
        assertEquals("2026-09-28 07:00:01", view.certCheckedAt());
    }

    @Test
    void 未探测档案观测列为NULL诚实占位不造假() throws Exception {
        String coreJson = """
                {"status":"unknown","checkedAt":"","msg":"",
                 "tlsMode":"strict","certIssuer":null,"certNotAfter":null,
                 "certChainComplete":null,"certCheckedAt":null}
                """;
        EnvHealthView view = mapper.readValue(coreJson, EnvHealthView.class);
        assertEquals("strict", view.tlsMode());
        assertNull(view.certIssuer());
        assertNull(view.certNotAfter());
        assertNull(view.certChainComplete());
        assertNull(view.certCheckedAt());
    }

    @Test
    void insecure档链完整性不谎报() throws Exception {
        EnvHealthView view = new EnvHealthView("healthy", "2026-09-28 07:00:00", "",
                "insecure", "CN=selfsigned.testpike.local", "2036-09-25", false, "2026-09-28 07:00:01");
        assertEquals("insecure", view.tlsMode());
        assertFalse(view.certChainComplete());
    }

    @Test
    void 旧三参核心载荷尾追字段缺位为null不炸桥() throws Exception {
        String legacyJson = "{\"status\":\"unhealthy\",\"checkedAt\":\"\",\"msg\":\"连接超时\"}";
        EnvHealthView view = mapper.readValue(legacyJson, EnvHealthView.class);
        assertEquals("连接超时", view.msg());
        assertNull(view.tlsMode());
        assertNull(view.certCheckedAt());
    }
}
