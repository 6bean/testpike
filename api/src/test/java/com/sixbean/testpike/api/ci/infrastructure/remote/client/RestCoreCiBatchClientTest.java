package com.sixbean.testpike.api.ci.infrastructure.remote.client;

import com.sixbean.testpike.api.ci.api.dto.CiBadgeView;
import com.sixbean.testpike.api.ci.api.dto.CiCreateFromTemplateInput;
import com.sixbean.testpike.api.ci.api.dto.CiEnvOverrideInput;
import com.sixbean.testpike.api.mcp.infrastructure.remote.StubCoreHttpServer;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RestCoreCiBatchClientTest {

    private StubCoreHttpServer core;
    private RestCoreCiBatchClient client;

    @BeforeEach
    void startCore() throws IOException {
        core = StubCoreHttpServer.start();
        client = new RestCoreCiBatchClient(core.baseUrl());
    }

    @AfterEach
    void stopCore() {
        core.close();
    }

    
    private static String operatorOf(StubCoreHttpServer.RecordedCall call) {
        List<String> headers = call.operatorHeaders();
        return headers.isEmpty() ? null : headers.get(headers.size() - 1);
    }

    @Test
    void badge链路_按token查视图_零操作人头() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"batchId\":7,\"status\":6,\"statusLabel\":\"已完成\",\"passRate\":0.5000}"));
        CiBadgeView view = client.badge("2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1");
        assertEquals(7L, view.batchId());
        assertEquals("已完成", view.statusLabel());
        assertEquals("通过率 50.0%", view.passRatePhrase(), "短语渲染自 core 数值，api 零重算");
        var call = core.call(0);
        assertEquals("GET", call.method());
        assertEquals("/internal/batches/badge/2f7a0d5c-1c1c-4b0e-9a10-24f1e4f9c3d1", call.path());
        assertEquals(List.of(), call.operatorHeaders(), "badge 读路径无操作人语义");
    }

    @Test
    void badgeByBatchId同源链路_按id查视图() {
        core.enqueue(StubCoreHttpServer.ok(
                "{\"batchId\":9,\"status\":4,\"statusLabel\":\"执行中\",\"passRate\":null}"));
        CiBadgeView view = client.badgeByBatchId(9L);
        assertEquals("/internal/batches/9/badge", core.call(0).path());
        assertEquals("暂无终态任务", view.passRatePhrase(), "NULL 率人话承 core 语义");
    }

    @Test
    void start链路_envOverrides包体与操作人头透传() {
        core.enqueue(StubCoreHttpServer.ok("{\"batchId\":7,\"status\":2}"));
        Map<String, Object> result = client.start(7L,
                new CiEnvOverrideInput("https://ci-target.example.com", null), 4242L);
        assertEquals(2, ((Number) result.get("status")).intValue());
        var call = core.call(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/batches/7/start", call.path());
        assertEquals("4242", operatorOf(call), "X-Operator-Id=Token 属主（审计归属原点）");
        assertTrue(call.body().contains("envOverrides"), "覆盖必须包在 BatchStartInput 体内: " + call.body());
        assertTrue(call.body().contains("ci-target.example.com"));
    }

    @Test
    void start链路_无覆盖零body走core两参口语义() {
        core.enqueue(StubCoreHttpServer.ok("{\"batchId\":7,\"status\":2}"));
        client.start(7L, null, 4242L);
        assertEquals("", core.call(0).body(), "无覆盖禁发空 envOverrides 体（Web 零影响语义归一）");
    }

    @Test
    void core业务失败信封_人话ServiceException透传() {
        core.enqueue(StubCoreHttpServer.fail("40401", "徽章不存在（批次可能已删除）"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> client.badge("00000000-0000-0000-0000-000000000000"));
        assertEquals("徽章不存在（批次可能已删除）", ex.getMessage());
        assertEquals(40401, ex.getCode());
    }

    @Test
    void 模板复用链路_操作人头透传() {
        core.enqueue(StubCoreHttpServer.ok("{\"id\":77,\"status\":1}"));
        client.createFromTemplate(new CiCreateFromTemplateInput(5L, "CI-冒烟", 3L), 4242L);
        var call = core.call(0);
        assertEquals("POST", call.method());
        assertEquals("/internal/batches/from-template", call.path());
        assertEquals("4242", operatorOf(call));
        assertTrue(call.body().contains("\"templateId\":5"), call.body());
    }

    @Test
    void 模板列表链路_按项目查询() {
        core.enqueue(StubCoreHttpServer.ok("[{\"id\":5,\"projectId\":3,\"name\":\"冒烟模板\"}]"));
        List<Map<String, Object>> templates = client.listTemplates(3L);
        assertEquals("/internal/batches/templates", core.call(0).path());
        assertEquals("projectId=3", core.call(0).query());
        assertEquals("冒烟模板", templates.get(0).get("name"));
    }
}
