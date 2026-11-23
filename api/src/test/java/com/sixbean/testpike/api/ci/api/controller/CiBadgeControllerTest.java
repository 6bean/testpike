package com.sixbean.testpike.api.ci.api.controller;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import com.sixbean.testpike.api.ci.domain.component.CiBadgeSvgRenderer;
import com.sixbean.testpike.api.ci.infrastructure.remote.client.CoreCiBatchClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CiBadgeControllerTest {

    
    private static final class StubClient implements CoreCiBatchClient {
        private String lastToken;

        @Override
        public CiBadgeView badge(String badgeToken) {
            this.lastToken = badgeToken;
            return new CiBadgeView(7L, 6, "已完成", new BigDecimal("0.7500"));
        }

        @Override
        public CiBadgeView badgeByBatchId(long batchId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> verdict(long batchId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> get(long batchId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Map<String, Object>> listCases(long batchId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Map<String, Object>> listTemplates(long projectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> createFromTemplate(
                com.sixbean.testpike.api.ci.api.dto.CiCreateFromTemplateInput input, long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> start(long batchId,
                com.sixbean.testpike.api.ci.api.dto.CiEnvOverrideInput envOverrides, long operatorId) {
            throw new UnsupportedOperationException();
        }
    }

    private final StubClient client = new StubClient();
    private final CiBadgeController controller = new CiBadgeController(client, new CiBadgeSvgRenderer());

    @Test
    void badge_SVG含状态与通过率_缓存头60s公开() {
        ResponseEntity<String> response = controller.badge("2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1");
        assertEquals("2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1", client.lastToken);
        String cacheControl = response.getHeaders().getCacheControl();
        assertTrue(cacheControl.contains("public") && cacheControl.contains("max-age=60"),
                "02 契约缓存 60s 公开（IT-I8-02）: " + cacheControl);
        assertEquals("image/svg+xml", String.valueOf(response.getHeaders().getContentType()));
        String svg = response.getBody();
        assertTrue(svg != null && svg.startsWith("<svg"), "SVG 体");
        assertTrue(svg.contains("已完成"), "状态一句话: " + svg);
        assertTrue(svg.contains("通过率 75.0%"), "通过率一句话: " + svg);
    }
}
