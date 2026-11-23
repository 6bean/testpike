package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;


class KnowledgeSearchConfigResolverTest {

    private KnowledgeSearchConfigResolver resolver(Map<String, String> k8) {
        CoreGateway stub = new CoreGateway("http://localhost:8083") {
            @Override
            public String getSysConfig(String key) {
                return k8.get(key);
            }
        };
        return new KnowledgeSearchConfigResolver(stub, new KnowledgeSearchProperties());
    }

    @Test
    void K8合法值六键全生效() {
        Map<String, String> k8 = new HashMap<>();
        k8.put("kb.reweight-excellent", "1.5");
        k8.put("kb.reweight-spec", "1.3");
        k8.put("kb.reweight-feedback", "0.7");
        k8.put("kb.lang-bonus", "0.1");
        k8.put("kb.fusion-mode", "weighted");
        k8.put("kb.global-weight", "0.4");
        KnowledgeSearchConfigResolver r = resolver(k8);
        assertEquals(1.5, r.reweightExcellent(), 1e-9);
        assertEquals(1.3, r.reweightSpec(), 1e-9);
        assertEquals(0.7, r.reweightFeedback(), 1e-9);
        assertEquals(0.1, r.langBonus(), 1e-9);
        assertEquals("weighted", r.fusionMode());
        assertEquals(0.4, r.globalWeight(), 1e-9);
    }

    @Test
    void IT_D3_03_reweightExcellent域1_0至2_0_两端点生效越界回退() {
        assertEquals(1.0, resolver(Map.of("kb.reweight-excellent", "1.0")).reweightExcellent(), 1e-9);
        assertEquals(2.0, resolver(Map.of("kb.reweight-excellent", "2.0")).reweightExcellent(), 1e-9);
        assertEquals(1.2, resolver(Map.of("kb.reweight-excellent", "0.99")).reweightExcellent(), 1e-9);
        assertEquals(1.2, resolver(Map.of("kb.reweight-excellent", "2.01")).reweightExcellent(), 1e-9);
        assertEquals(1.2, resolver(Map.of("kb.reweight-excellent", "abc")).reweightExcellent(), 1e-9);
    }

    @Test
    void fusionMode白名单_非法值回退rrf() {
        assertEquals("rrf", resolver(Map.of("kb.fusion-mode", "rrf")).fusionMode());
        assertEquals("weighted", resolver(Map.of("kb.fusion-mode", "weighted")).fusionMode());
        assertEquals("rrf", resolver(Map.of("kb.fusion-mode", "bge")).fusionMode());
        assertEquals(0.8, resolver(Map.of("kb.reweight-feedback", "1.5")).reweightFeedback(), 1e-9);
        assertEquals(0.05, resolver(Map.of("kb.lang-bonus", "0.9")).langBonus(), 1e-9);
        assertEquals(0.3, resolver(Map.of("kb.global-weight", "-0.1")).globalWeight(), 1e-9);
    }

    @Test
    void K8不可达_回退配置文件兜底值() {
        CoreGateway stub = new CoreGateway("http://localhost:8083") {
            @Override
            public String getSysConfig(String key) {
                return null;
            }
        };
        KnowledgeSearchProperties props = new KnowledgeSearchProperties();
        props.setReweightExcellent(1.4);
        props.setFusionMode("weighted");
        KnowledgeSearchConfigResolver r = new KnowledgeSearchConfigResolver(stub, props);
        assertEquals(1.4, r.reweightExcellent(), 1e-9);
        assertEquals("weighted", r.fusionMode());
        assertEquals(1.1, r.reweightSpec(), 1e-9);
        assertEquals(60, r.rrfK());
    }
}
