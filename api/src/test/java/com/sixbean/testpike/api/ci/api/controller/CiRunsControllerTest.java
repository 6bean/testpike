package com.sixbean.testpike.api.ci.api.controller;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import com.sixbean.testpike.api.ci.api.dto.CiCreateFromTemplateInput;
import com.sixbean.testpike.api.ci.api.dto.CiEnvOverrideInput;
import com.sixbean.testpike.api.ci.api.dto.CiRunInput;
import com.sixbean.testpike.api.ci.domain.component.CiBearerGate;
import com.sixbean.testpike.api.ci.domain.component.CiRunValidator;
import com.sixbean.testpike.api.ci.infrastructure.remote.client.CoreCiBatchClient;
import com.sixbean.testpike.api.mcp.domain.component.McpBearerAuthenticator;
import com.sixbean.testpike.api.mcp.infrastructure.remote.ApiTokenVerifyClient;
import com.sixbean.testpike.api.mcp.infrastructure.remote.McpVerifyOutcome;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.idempotent.IdempotentGuard;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class CiRunsControllerTest {

    
    private static final class StubVerifyClient implements ApiTokenVerifyClient {
        @Override
        public McpVerifyOutcome verify(String plaintext) {
            return McpVerifyOutcome.ok(4242L, List.of("ci"));
        }
    }

    
    private static final class StubBatchClient implements CoreCiBatchClient {
        final List<String> calls = new ArrayList<>();
        long lastStartBatchId;
        CiEnvOverrideInput lastOverrides;
        long lastOperator;
        CiCreateFromTemplateInput lastTemplateInput;
        List<Map<String, Object>> templates = List.of();
        long createdBatchId = 91L;

        @Override
        public CiBadgeView badge(String badgeToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CiBadgeView badgeByBatchId(long batchId) {
            calls.add("badgeByBatchId:" + batchId);
            return new CiBadgeView(batchId, 6, "已完成", new BigDecimal("0.7500"));
        }

        @Override
        public Map<String, Object> get(long batchId) {
            calls.add("get:" + batchId);
            return Map.of("id", batchId, "status", 6, "statusLabel", "已完成");
        }

        @Override
        public List<Map<String, Object>> listCases(long batchId) {
            calls.add("listCases:" + batchId);
            return List.of(Map.of("caseNo", "TC-1", "resultLabel", "通过"));
        }

        @Override
        public List<Map<String, Object>> listTemplates(long projectId) {
            calls.add("listTemplates:" + projectId);
            return templates;
        }

        @Override
        public Map<String, Object> createFromTemplate(CiCreateFromTemplateInput input, long operatorId) {
            calls.add("createFromTemplate:" + input.templateId());
            this.lastTemplateInput = input;
            this.lastOperator = operatorId;
            return Map.of("id", createdBatchId);
        }

        @Override
        public Map<String, Object> start(long batchId, CiEnvOverrideInput envOverrides, long operatorId) {
            calls.add("start:" + batchId);
            this.lastStartBatchId = batchId;
            this.lastOverrides = envOverrides;
            this.lastOperator = operatorId;
            return Map.of("batchId", batchId, "status", 3, "statusLabel", "执行中");
        }

        @Override
        public Map<String, Object> verdict(long batchId) {
            calls.add("verdict:" + batchId);
            return Map.of("verdict", "fail", "unmetItems", List.of("P0失败明细1"));
        }
    }

    private static StringRedisTemplate redis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private final StubBatchClient batchClient = new StubBatchClient();
    private final CiRunsController controller = new CiRunsController(
            new CiBearerGate(new McpBearerAuthenticator(new StubVerifyClient())),
            new CiRunValidator(), batchClient, new IdempotentGuard(redis()));

    private static String freshKey() {
        return "it-i8b-" + UUID.randomUUID();
    }

    @Test
    void batch链路_直start_覆盖映射与操作人透传() {
        Response<Map<String, Object>> resp = controller.run("Bearer tp_pat_x", freshKey(),
                new CiRunInput(7L, null, null, null, Map.of("url", "http://staging.test")));
        assertEquals(7L, batchClient.lastStartBatchId);
        assertEquals("http://staging.test", batchClient.lastOverrides.url(),
                "envOverrides Map→CiEnvOverrideInput 映射");
        assertEquals(null, batchClient.lastOverrides.accountRef());
        assertEquals(4242L, batchClient.lastOperator, "operator=principal.userId() 透 3 参 start");
        assertEquals(7L, ((Number) resp.getData().get("batchId")).longValue());
        assertEquals(List.of("start:7"), batchClient.calls);
    }

    @Test
    void 同幂等键回放_不重复执行() {
        String key = freshKey();
        controller.run("Bearer tp_pat_x", key, new CiRunInput(8L, null, null, null, null));
        Response<Map<String, Object>> replay = controller.run("Bearer tp_pat_x", key,
                new CiRunInput(8L, null, null, null, null));
        assertEquals(List.of("start:8"), batchClient.calls, "同键二发回放原结果，零重复执行");
        assertEquals(8L, ((Number) replay.getData().get("batchId")).longValue(), "回放体与原结果一致");
    }

    @Test
    void 缺IdempotencyKey_400人话且零执行() {
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.run(
                "Bearer tp_pat_x", null, new CiRunInput(9L, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("Idempotency-Key"), "人话点名缺的键: " + ex.getMessage());
        assertTrue(batchClient.calls.isEmpty(), "缺键不得执行任何批次候选端点");
    }

    @Test
    void profile链路_模板名精确匹配唯一_建批后start() {
        batchClient.templates = List.of(
                Map.of("id", 3L, "name", "nightly-regression"),
                Map.of("id", 4L, "name", "smoke"));
        controller.run("Bearer tp_pat_x", freshKey(),
                new CiRunInput(null, "nightly-regression", 1L, 5L, null));
        assertEquals(3L, batchClient.lastTemplateInput.templateId(), "匹配名=精确非前缀");
        assertEquals(5L, batchClient.lastTemplateInput.envId());
        assertEquals(List.of("listTemplates:1", "createFromTemplate:3", "start:91"), batchClient.calls,
                "建批→start 链，start 用新批 id");
        assertEquals(4242L, batchClient.lastOperator);
    }

    @Test
    void profile链路_无同名模板_404人话() {
        batchClient.templates = List.of(Map.of("id", 4L, "name", "smoke"));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.run(
                "Bearer tp_pat_x", freshKey(), new CiRunInput(null, "nope", 1L, 5L, null)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("nope"), "人话点名模板名: " + ex.getMessage());
        assertTrue(batchClient.calls.stream().noneMatch(c -> c.startsWith("start")), "零启动");
    }

    @Test
    void profile链路_同名多模板_400指引以batch消歧() {
        batchClient.templates = List.of(
                Map.of("id", 4L, "name", "dupe"),
                Map.of("id", 5L, "name", "dupe"));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.run(
                "Bearer tp_pat_x", freshKey(), new CiRunInput(null, "dupe", 1L, 5L, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("batch"), "指路 batch 消歧: " + ex.getMessage());
    }

    @Test
    void status轮询_真链get透传() {
        Response<Map<String, Object>> resp = controller.status("Bearer tp_pat_x", 66L);
        assertEquals(List.of("get:66"), batchClient.calls);
        assertEquals(66L, ((Number) resp.getData().get("id")).longValue());
    }

    @Test
    void report口径_badge同源数值直出禁自算() {
        Response<Map<String, Object>> resp = controller.report("Bearer tp_pat_x", 77L);
        Map<String, Object> data = resp.getData();
        assertEquals(0, new BigDecimal("0.7500").compareTo((BigDecimal) data.get("passRate")),
                "pass-rate 唯一定义点=core 同源链路（红线⑥）");
        assertEquals("已完成", data.get("statusLabel"));
        assertEquals(1, ((List<?>) data.get("cases")).size(), "明细用例来自 core listCases");
        assertEquals(List.of("badgeByBatchId:77", "listCases:77"), batchClient.calls);
    }

    
    @Test
    void verdict端点_core判定直透禁自算() {
        Response<Map<String, Object>> resp = controller.verdict("Bearer tp_pat_x", 88L);
        assertEquals(List.of("verdict:88"), batchClient.calls);
        Map<String, Object> data = resp.getData();
        assertEquals("fail", data.get("verdict"), "core 判定结论直出（桩值）");
        assertEquals(List.of("P0失败明细1"), data.get("unmetItems"));
    }

    
    @Test
    void verdict端点_scope不足_403人话() {
        CiBearerGate noScope = new CiBearerGate(
                new McpBearerAuthenticator(new StubScopeClient()));
        CiRunsController gated = new CiRunsController(noScope, new CiRunValidator(),
                batchClient, new IdempotentGuard(redis()));
        ServiceException ex = assertThrows(ServiceException.class, () -> gated.verdict("Bearer x", 1L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("ci"), ex.getMessage());
        assertTrue(batchClient.calls.isEmpty(), "认证不足=零 core 调用");
    }

    
    private static final class StubScopeClient implements ApiTokenVerifyClient {
        @Override
        public McpVerifyOutcome verify(String plaintext) {
            return McpVerifyOutcome.ok(4242L, List.of("mcp.tools"));
        }
    }
}
