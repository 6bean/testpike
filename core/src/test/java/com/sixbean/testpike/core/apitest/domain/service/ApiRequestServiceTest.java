package com.sixbean.testpike.core.apitest.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ApiRequestServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> requestTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        ProjectFixtureRig.sweep(jdbcTemplate, requestTrackedProjects);
    }

    private long newCollection() {
        long pid = ProjectFixtureRig.create(jdbcTemplate, projectService, 1L, requestTrackedProjects,
                "项目REQ", "REQ" + System.nanoTime());
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(pid, 0L, "支付域", null));
        return root.id();
    }

    private RequestSaveInput validInput(long collectionId, String name) {
        return new RequestSaveInput(collectionId, name, "GET", "/api/v1/orders",
                "[]", "", "{}", "[]");
    }

    // ---- part 1: request transport protocol (http / ws) ----

    /** Eleven-arg canonical form with an explicit transport protocol. */
    private RequestSaveInput withProtocol(long collectionId, String name, String protocol) {
        return new RequestSaveInput(collectionId, name, "GET", "/api/v1/orders",
                "[]", "", "{}", "[]", null, null, protocol);
    }

    @Test
    void test_protocol_defaults_to_http_when_absent() {
        long cid = newCollection();
        ApiRequestInfo created = apiRequestService.create(1L, validInput(cid, "旧形状无protocol"));
        assertEquals("http", created.protocol(), "缺省=HTTP（存量行零漂移）");
        assertEquals("http", apiRequestService.get(created.id()).protocol(), "回读同为 http");
    }

    @Test
    void test_ws_protocol_persists_and_roundtrips() {
        long cid = newCollection();
        ApiRequestInfo created = apiRequestService.create(1L,
                withProtocol(cid, "ws握手", "WS"));
        assertEquals("ws", created.protocol(), "保存期归一小写");
        assertEquals("ws", apiRequestService.get(created.id()).protocol(), "回读列原文");

        // update replaces the protocol like every other column (full-save semantics)
        apiRequestService.update(1L, created.id(), withProtocol(cid, "ws握手", "http"));
        assertEquals("http", apiRequestService.get(created.id()).protocol());
    }

    @Test
    void test_unknown_protocol_refused_at_save_fail_closed() {
        long cid = newCollection();
        // grpc/wss are outside the http/ws vocabulary — fail closed with a human name
        ServiceException e = assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, withProtocol(cid, "怪协议", "grpc")));
        assertTrue(e.getMessage().contains("grpc"), "文案点名非法值: " + e.getMessage());
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, withProtocol(cid, "伪ws", "wss")));
    }

    @Test
    void test_assertion_grammar_is_protocol_crossed_at_save() throws Exception {
        long cid = newCollection();
        // ws face: ws.* rows pass, HTTP-type rows are refused naming the transport
        RequestSaveInput wsGood = new RequestSaveInput(cid, "ws收消息", "GET", "/ws/orders",
                "[]", "", "{}", "[{\"type\":\"ws.receive\",\"expect\":\"pong\"}]",
                null, null, "ws");
        ApiRequestInfo created = apiRequestService.create(1L, wsGood);
        assertEquals("ws", created.protocol());

        RequestSaveInput wsBad = new RequestSaveInput(cid, "ws带状态码", "GET", "/ws/orders",
                "[]", "", "{}", "[{\"type\":\"status\",\"expect\":200}]",
                null, null, "ws");
        ServiceException e = assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, wsBad));
        assertTrue(e.getMessage().contains("不适用于 WebSocket 传输面"),
                "ws 面 HTTP 型断言点名: " + e.getMessage());

        // http face: ws.* rows are refused there too (they only parse on ws requests)
        RequestSaveInput httpWsRow = new RequestSaveInput(cid, "http带ws断言", "GET", "/api/v1/orders",
                "[]", "", "{}", "[{\"type\":\"ws.order\",\"expect\":[\"A\",\"B\"]}]",
                null, null, "http");
        ServiceException e2 = assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, httpWsRow));
        assertTrue(e2.getMessage().contains("不适用于 HTTP 传输面"),
                "http 面 ws 型断言点名: " + e2.getMessage());

        // shape bounds on the ws face fail closed with the shared anchors
        RequestSaveInput wsBadWindow = new RequestSaveInput(cid, "窗口零", "GET", "/ws/orders",
                "[]", "", "{}", "[{\"type\":\"ws.receive\",\"expect\":\"x\",\"withinMs\":0}]",
                null, null, "ws");
        assertThrows(ServiceException.class, () -> apiRequestService.create(1L, wsBadWindow));
    }

    // ---- orchestration storage + save gate (precondition / loop) ----

    private RequestSaveInput withOrchestration(long collectionId, String name,
                                               String preconditionJson, String loopJson) {
        return new RequestSaveInput(collectionId, name, "GET", "/api/v1/orders",
                "[]", "", "{}", "[]", preconditionJson, loopJson);
    }

    @Test
    void test_orchestration_rows_persist_and_roundtrip() throws Exception {
        long cid = newCollection();
        ApiRequestInfo created = apiRequestService.create(1L, withOrchestration(cid, "带闸门",
                "{\"field\":\"status\",\"op\":\"eq\",\"expect\":200}",
                "{\"mode\":\"count\",\"times\":3,\"delayMs\":500}"));

        ApiRequestInfo got = apiRequestService.get(created.id());
        // jsonb round-trip reorders keys and adds spacing — parse by shape, not substring
        JsonNode precondition = MAPPER.readTree(got.preconditionJson());
        assertEquals("status", precondition.path("field").asText());
        assertEquals("eq", precondition.path("op").asText());
        assertEquals(200, precondition.path("expect").asInt());
        JsonNode loop = MAPPER.readTree(got.loopJson());
        assertEquals("count", loop.path("mode").asText());
        assertEquals(3, loop.path("times").asInt(), "循环列原文回读: " + got.loopJson());
        assertEquals(500, loop.path("delayMs").asInt());

        // update replaces both rows (full-save semantics, same as other columns)
        apiRequestService.update(1L, created.id(), withOrchestration(cid, "带闸门",
                "{\"field\":\"var\",\"name\":\"token\",\"op\":\"exists\"}", "{}"));
        ApiRequestInfo upd = apiRequestService.get(created.id());
        assertTrue(upd.preconditionJson().contains("exists"));
        assertEquals("{}", upd.loopJson(), "空循环对象=顺跑（可清除闸门）");
    }

    @Test
    void test_orchestration_defaults_to_empty_object_when_absent() {
        long cid = newCollection();
        ApiRequestInfo created = apiRequestService.create(1L, validInput(cid, "旧形状八参"));
        assertEquals("{}", created.preconditionJson(), "缺省=恒运行");
        assertEquals("{}", created.loopJson(), "缺省=顺跑");
    }

    @Test
    void test_invalid_precondition_refused_at_save() {
        long cid = newCollection();
        // op gt is outside the vocabulary (single source ApiOrchestrationContract)
        ServiceException e = assertThrows(ServiceException.class, () -> apiRequestService.create(
                1L, withOrchestration(cid, "非法前置",
                        "{\"field\":\"status\",\"op\":\"gt\",\"expect\":200}", "{}")));
        assertTrue(e.getMessage().contains("eq / ne / in"), e.getMessage());

        // a variable name must be a dotted identifier
        assertThrows(ServiceException.class, () -> apiRequestService.create(
                1L, withOrchestration(cid, "非法变量名",
                        "{\"field\":\"var\",\"name\":\"1-bad\",\"op\":\"exists\"}", "{}")));
    }

    @Test
    void test_invalid_loop_refused_at_save() {
        long cid = newCollection();
        // count loop above the ceiling
        ServiceException e = assertThrows(ServiceException.class, () -> apiRequestService.create(
                1L, withOrchestration(cid, "超上限循环", "{}",
                        "{\"mode\":\"count\",\"times\":101}")));
        assertTrue(e.getMessage().contains("1~100"), e.getMessage());

        // until loop without the maxTimes guard
        assertThrows(ServiceException.class, () -> apiRequestService.create(
                1L, withOrchestration(cid, "无上限until", "{}",
                        "{\"mode\":\"until\",\"until\":{\"field\":\"status\",\"op\":\"eq\",\"expect\":200}}")));

        // unknown loop mode
        assertThrows(ServiceException.class, () -> apiRequestService.create(
                1L, withOrchestration(cid, "非法模式", "{}", "{\"mode\":\"forever\"}")));
    }

    @Test
    void test_request_crud_lifecycle() {
        long cid = newCollection();
        ApiRequestInfo created = apiRequestService.create(1L, validInput(cid, "查询订单列表"));
        assertNotNull(created.id());
        assertEquals(cid, created.collectionId());
        assertEquals("GET", created.method());
        assertEquals("/api/v1/orders", created.urlPath());

        ApiRequestInfo got = apiRequestService.get(created.id());
        assertEquals("查询订单列表", got.name());

        RequestSaveInput upd = new RequestSaveInput(cid, "创建订单", "POST", "/api/v1/orders",
                "[{\"name\":\"Content-Type\",\"value\":\"application/json\"}]",
                "{\"amount\":100}", "{}", "[]");
        apiRequestService.update(1L, created.id(), upd);
        ApiRequestInfo after = apiRequestService.get(created.id());
        assertEquals("POST", after.method());
        assertEquals("创建订单", after.name());
        assertTrue(after.bodyText().contains("amount"));

        apiRequestService.create(1L, validInput(cid, "退款接口"));
        List<ApiRequestInfo> list = apiRequestService.list(cid);
        assertEquals(2, list.size());

        apiRequestService.delete(1L, created.id());
        assertThrows(ServiceException.class, () -> apiRequestService.get(created.id()));
        assertThrows(ServiceException.class, () -> apiRequestService.delete(1L, created.id()));
    }

    @Test
    void test_reorder_persists_explicit_tree_order() {
        // collection members carry a user-maintained order (the
        // run-set launch preview and whole-set execution freeze definitions in
        // that order); reorder replaces the order atomically and honestly —
        // a stale id set (someone else added/removed a request meanwhile)
        // must be rejected instead of silently dropping rows.
        long cid = newCollection();
        ApiRequestInfo r1 = apiRequestService.create(1L, validInput(cid, "登录"));
        ApiRequestInfo r2 = apiRequestService.create(1L, validInput(cid, "建单"));
        ApiRequestInfo r3 = apiRequestService.create(1L, validInput(cid, "查单"));

        apiRequestService.reorder(1L, cid, List.of(r3.id(), r1.id(), r2.id()));
        List<ApiRequestInfo> after = apiRequestService.list(cid);
        assertEquals(List.of(r3.id(), r1.id(), r2.id()),
                after.stream().map(ApiRequestInfo::id).toList());

        // A later create appends at the tail (max+1), never jumps ahead.
        ApiRequestInfo r4 = apiRequestService.create(1L, validInput(cid, "退款"));
        List<ApiRequestInfo> withNew = apiRequestService.list(cid);
        assertEquals(r4.id(), withNew.get(withNew.size() - 1).id());

        // Stale set: missing one member -> 400 human message, order untouched.
        ServiceException e = assertThrows(ServiceException.class, () ->
                apiRequestService.reorder(1L, cid, List.of(r3.id(), r1.id())));
        assertTrue(e.getMessage().contains("不一致"), "文案应人话点名集合成员不一致：" + e.getMessage());
        List<ApiRequestInfo> untouched = apiRequestService.list(cid);
        assertEquals(List.of(r3.id(), r1.id(), r2.id(), r4.id()),
                untouched.stream().map(ApiRequestInfo::id).toList());

        // Foreign id (request of another collection) -> 400 as well.
        long other = newCollection();
        ApiRequestInfo foreign = apiRequestService.create(1L, validInput(other, "别集请求"));
        assertThrows(ServiceException.class, () ->
                apiRequestService.reorder(1L, cid, List.of(r1.id(), r2.id(), r3.id(), r4.id(), foreign.id())));
    }

    @Test
    void test_ssrf_absolute_url_rejected_at_save() {
        long cid = newCollection();
        for (String bad : new String[]{"http://evil.example.com/api", "https://169.254.169.254/latest",
                "//evil.example.com/x", "api/v1/no-leading-slash"}) {
            ServiceException e = assertThrows(ServiceException.class, () ->
                    apiRequestService.create(1L, new RequestSaveInput(cid, "坏请求", "GET", bad,
                            "[]", "", "{}", "[]")));
            assertTrue(e.getMessage().contains("路径"), "文案应人话点名 path：" + e.getMessage());
        }
    }

    @Test
    void test_method_and_json_shape_validation() {
        long cid = newCollection();
        ServiceException m = assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏动词", "TRACE", "/x",
                        "[]", "", "{}", "[]")));
        assertTrue(m.getMessage().contains("TRACE"));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏头", "GET", "/x",
                        "{not json}", "", "{}", "[]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏头2", "GET", "/x",
                        "{}", "", "{}", "[]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏auth", "GET", "/x",
                        "[]", "", "[]", "[]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "  ", "GET", "/x",
                        "[]", "", "{}", "[]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(999999999L, "孤儿", "GET", "/x",
                        "[]", "", "{}", "[]")));
    }

    @Test
    void test_assertion_rows_static_validation_single_source() {
        long cid = newCollection();
        String good = """
                [
                  {"type":"status","expect":200},
                  {"type":"duration","op":"range","expect":[0,3000]},
                  {"type":"body","path":"$.data.id","op":"exact","expect":"10086"},
                  {"type":"extract","path":"$.data.token","saveAs":"TOKEN"}
                ]""";
        ApiRequestInfo ok = apiRequestService.create(1L, new RequestSaveInput(cid, "带断言", "GET",
                "/api/v1/pay", "[]", "", "{}", good));
        assertNotNull(ok.id());

        ServiceException jp = assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏断言", "GET", "/x",
                        "[]", "", "{}",
                        "[{\"type\":\"body\",\"path\":\"$.a[?(@.b>1)]\",\"op\":\"exact\",\"expect\":1}]")));
        assertTrue(jp.getMessage().contains("受限子集"), "应回契约文案：" + jp.getMessage());

        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏类型", "GET", "/x",
                        "[]", "", "{}", "[{\"type\":\"script\",\"expect\":\"return true\"}]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏op", "GET", "/x",
                        "[]", "", "{}",
                        "[{\"type\":\"body\",\"path\":\"$.a\",\"op\":\"startswith\",\"expect\":\"x\"}]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏提取", "GET", "/x",
                        "[]", "", "{}", "[{\"type\":\"extract\",\"path\":\"$.a\"}]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏提取2", "GET", "/x",
                        "[]", "", "{}",
                        "[{\"type\":\"extract\",\"path\":\"$.a\",\"saveAs\":\"1 bad name\"}]")));
        assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, "坏状态", "GET", "/x",
                        "[]", "", "{}", "[{\"type\":\"status\",\"expect\":\"abc\"}]")));
    }

    @Test
    void test_auth_envelope_static_validation_single_source() {
        long cid = newCollection();
        // W1c auth presets: shape + credential-reference red line enforced at save time
        // (single source AuthFormContract; the executor accepts the same five types).
        assertAuthRejected(cid, "坏auth型", "{\"type\":\"digest\"}", "digest");
        assertAuthRejected(cid, "明文token",
                "{\"type\":\"bearer\",\"token\":\"" + "Sup" + "3rSecret" + "\"}", "@cred");
        assertAuthRejected(cid, "缺装配行", "{\"type\":\"signature\"}", "assemblies");
        // Legal shapes: @cred/ reference, {{CRED:key}} placeholder and signature assembly rows
        assertNotNull(apiRequestService.create(1L, new RequestSaveInput(cid, "好auth", "GET", "/x",
                "[]", "", "{\"type\":\"bearer\",\"token\":\"@cred/prod_token\"}", "[]")).id());
        // {{CRED:key}} vault placeholder is the other legal reference form (value
        // injection lands with the dispatch-side step; the save gate only checks shape)
        assertNotNull(apiRequestService.create(1L, new RequestSaveInput(cid, "占位auth", "GET", "/x",
                "[]", "", "{\"type\":\"bearer\",\"token\":\"{{CRED:prod_token}}\"}", "[]")).id());
        assertNotNull(apiRequestService.create(1L, new RequestSaveInput(cid, "好auth2", "GET", "/x",
                "[]", "", "{\"type\":\"signature\",\"assemblies\":[{\"into\":\"header\","
                        + "\"name\":\"X-Sign\",\"value\":\"${gen.hmacSha256(text=a,key=b)}\"}]}", "[]")).id());
    }

    /** Save-chain auth verdict helper: rejected shapes must raise ServiceException naming a fragment. */
    private void assertAuthRejected(long cid, String name, String authJson, String fragment) {
        ServiceException e = assertThrows(ServiceException.class, () ->
                apiRequestService.create(1L, new RequestSaveInput(cid, name, "GET", "/x",
                        "[]", "", authJson, "[]")));
        assertTrue(e.getMessage().contains(fragment),
                "message should name the offending shape: " + e.getMessage());
    }

    @Test
    void test_collection_delete_blocked_when_requests_exist() {
        long cid = newCollection();
        apiRequestService.create(1L, validInput(cid, "在途请求"));
        ServiceException block = assertThrows(ServiceException.class, () ->
                apiCollectionService.delete(1L, cid));
        assertTrue(block.getMessage().contains("请求"));
        apiRequestService.delete(1L, apiRequestService.list(cid).get(0).id());
        apiCollectionService.delete(1L, cid);
    }
}
