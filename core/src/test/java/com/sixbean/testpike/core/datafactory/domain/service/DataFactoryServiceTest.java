package com.sixbean.testpike.core.datafactory.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryCreateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryQuery;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryUpdateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataPoolItemInfo;
import com.sixbean.testpike.core.datafactory.domain.service.impl.DataFactoryServiceImpl;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class DataFactoryServiceTest {

    @Autowired
    private DataFactoryService dataFactoryService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer httpServer;
    private String orderApiBase;

    private HttpServer echoServer;
    private String echoApiBase;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped tp_user (seed admin included!) and tp_project on the shared dev
 * database. tp_data_factory/tp_data_pool_item carry project_id without an
 * FK, hence the extras list; tp_data_factory_run hangs off the factory
 * through a plain BIGINT, so its join purge runs BEFORE the sweep while
 * factory rows are still present to join against.
 */
    private final java.util.List<Long> trackedProjects = new java.util.ArrayList<>();

    private static final java.util.List<String> PROJECT_KEYED_EXTRAS =
            java.util.List.of("tp_data_factory", "tp_data_pool_item");

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.childRowsOfProjects(jdbcTemplate, "tp_data_factory_run",
                "factory_id", "tp_data_factory", trackedProjects);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, PROJECT_KEYED_EXTRAS, trackedProjects);
        trackedProjects.clear();
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (echoServer != null) {
            echoServer.stop(0);
            echoServer = null;
        }
    }

    
    private void startEchoApi() throws Exception {
        echoServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        echoServer.createContext("/", this::handleEcho);
        echoServer.start();
        echoApiBase = "http://127.0.0.1:" + echoServer.getAddress().getPort();
    }

    private void handleEcho(HttpExchange exchange) throws java.io.IOException {
        String path = exchange.getRequestURI().getRawPath();
        String echoed = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        byte[] body = ("{\"echo\":\"" + echoed + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    
    private void startOrderApi() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/orders", this::handleOrders);
        httpServer.start();
        orderApiBase = "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    private void handleOrders(HttpExchange exchange) throws java.io.IOException {
        byte[] body = "{\"code\":\"0\",\"data\":[{\"orderNo\":\"ORD-1001\"},{\"orderNo\":\"ORD-1002\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
    }

    private long createEnv(long pid, String name) {
        return createEnvWithType(pid, name, 2);
    }

    
    private long createEnvWithType(long pid, String name, int envType) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, envType,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    private long createApiFactory(long pid, long envId, String name, String content) {
        return dataFactoryService.create(1L, new DataFactoryCreateInput(pid, envId, 1, name, content, null)).id();
    }


    @Test
    void apiFactory_run_orders_intoPool_withHistory() throws Exception {
        startOrderApi();
        long pid = createProject("P1");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"订单\",\"method\":\"GET\",\"url\":\"" + orderApiBase
                + "/orders\",\"headers\":{},\"body\":{},\"extractExpr\":\"$.data[*].orderNo\"}";
        long fid = createApiFactory(pid, envId, "下单造数", content);

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);

        assertEquals(2, result.createdCount(), result.message());
        assertEquals(2, result.poolCount(), "入池 2 条");
        assertEquals(2L, poolCount(envId), "tp_data_pool_item 落 2 条");
        assertEquals(1L, runCount(fid), "运行历史 1 条");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_factory_run WHERE factory_id = ? AND status = 2",
                Integer.class, fid), "run 状态=成功");

        DataFactoryRunResult again = dataFactoryService.run(1L, fid);
        assertEquals(0, again.poolCount(), "幂等去重 0 新增: " + again.message());
        assertEquals(2L, poolCount(envId), "池仍 2 条（幂等去重）");

        PageResult<DataFactoryRunInfo> runs = dataFactoryService.runs(1L, pid, fid, 1, 20);
        assertEquals(2, runs.total());
        assertTrue(runs.list().get(0).resultSummary().contains("造了 2 条"), runs.list().get(0).resultSummary());
    }


    @Test
    void sqlFactory_insertReturning_intoPool() {
        long pid = createProject("P2");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"账号\",\"sql\":\"INSERT INTO tp_project_env "
                + "(project_id, name, env_type, base_url) VALUES (" + pid
                + ", 'SQL造号环境', 3, 'https://sql.example.com') RETURNING name\"}";
        long fid = dataFactoryService.create(1L, new DataFactoryCreateInput(pid, envId, 2, "SQL 造号", content, null)).id();

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);

        assertEquals(1, result.createdCount(), result.message());
        assertEquals(1L, poolCount(envId));
    }


    @Test
    void scriptFactory_pythonStdoutJson_intoPool() {
        long pid = createProject("P3");
        long envId = createEnv(pid, "测试环境");
        String script = "# poolType=订单\nimport json\nprint(json.dumps([\"SCR-1\", \"SCR-2\"]))";
        long fid = dataFactoryService.create(1L, new DataFactoryCreateInput(pid, envId, 3, "脚本造数", script, null)).id();

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);

        assertEquals(2, result.createdCount());
        assertEquals(2L, poolCount(envId));
    }


    @Test
    void apiFactory_unreachable_failWithoutPoolWrite() {
        long pid = createProject("P4");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"订单\",\"method\":\"GET\",\"url\":\"http://127.0.0.1:1/orders\","
                + "\"headers\":{},\"body\":{},\"extractExpr\":\"$.data[*].orderNo\"}";
        long fid = createApiFactory(pid, envId, "坏造数", content);

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);

        assertEquals(0, result.createdCount(), "失败不产出");
        assertEquals(0L, poolCount(envId), "数据池不写入（IT-L5-01）");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_factory_run WHERE factory_id = ? AND status = 3",
                Integer.class, fid), "run 状态=失败");
        DataFactoryRunInfo last = dataFactoryService.runs(1L, pid, fid, 1, 20).list().get(0);
        assertTrue(last.resultSummary().contains("失败"), "失败原因已记录: " + last.resultSummary());
    }


    @Test
    void crud_listFilter_h1Isolation_andValidation() {
        long pidA = createProject("PA");
        long pidB = createProject("PB");
        long envA = createEnv(pidA, "测试环境");
        long envB = createEnv(pidB, "测试环境");
        long f1 = createApiFactory(pidA, envA, "下单造数A", "{\"poolType\":\"订单\",\"method\":\"GET\",\"url\":\"http://x/1\"}");
        long f2 = dataFactoryService.create(1L, new DataFactoryCreateInput(pidB, envB, 1, "下单造数B", "{\"poolType\":\"订单\"}", null)).id();
        long f3 = dataFactoryService.create(1L, new DataFactoryCreateInput(pidA, envA, 2, "SQL造数A", "{\"poolType\":\"账号\",\"sql\":\"SELECT 1\"}", null)).id();

        PageResult<DataFactoryInfo> pageA = dataFactoryService.list(1L, new DataFactoryQuery(pidA, null, null, 1, 20));
        assertEquals(2, pageA.total());
        PageResult<DataFactoryInfo> kw = dataFactoryService.list(1L, new DataFactoryQuery(pidA, "SQL", null, 1, 20));
        assertEquals(1, kw.total());
        PageResult<DataFactoryInfo> byType = dataFactoryService.list(1L, new DataFactoryQuery(pidA, null, 1, 1, 20));
        assertEquals(1, byType.total());

        dataFactoryService.update(1L, f1, new DataFactoryUpdateInput("下单造数A改", null, null, null));
        assertEquals("下单造数A改", dataFactoryService.get(1L, f1).name());
        assertThrows(ServiceException.class, () -> dataFactoryService.runs(1L, pidA, f2, 1, 20));
        assertThrows(ServiceException.class, () -> dataFactoryService.get(1L, 999999L));
        assertThrows(ServiceException.class, () -> dataFactoryService.create(1L,
                new DataFactoryCreateInput(pidA, envA, 9, "非法", "{}", null)));
        dataFactoryService.update(1L, f3, new DataFactoryUpdateInput(null, null, 2, null));
        assertThrows(ServiceException.class, () -> dataFactoryService.run(1L, f3));
        dataFactoryService.delete(1L, f3);
        assertThrows(ServiceException.class, () -> dataFactoryService.get(1L, f3));
    }


    @Test
    void pool_list_filter_decryptDisplay_statusUpdate_delete() throws Exception {
        startOrderApi();
        long pid = createProject("P2");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"订单\",\"method\":\"GET\",\"url\":\"" + orderApiBase
                + "/orders\",\"headers\":{},\"body\":{},\"extractExpr\":\"$.data[*].orderNo\"}";
        long fid = createApiFactory(pid, envId, "下单造数", content);
        dataFactoryService.run(1L, fid);

        PageResult<DataPoolItemInfo> page = dataFactoryService.listPoolItems(1L, pid, envId, "订单", null, 1, 10);
        assertEquals(2, page.total(), "池内 2 条");
        assertEquals(2, page.list().size());
        DataPoolItemInfo item = page.list().get(0);
        assertEquals(1, item.status(), "造数产出入池默认空闲（N2 新语义 1=空闲）");
        assertEquals("空闲", item.statusLabel(), "状态中文标签");
        assertTrue(item.value().startsWith("ORD-"), "value 解密展示明文: " + item.value());
        assertNotNull(item.envName(), "环境名回显");

        PageResult<DataPoolItemInfo> byType = dataFactoryService.listPoolItems(1L, pid, envId, "账号", null, 1, 10);
        assertEquals(0, byType.total(), "类型筛选无匹配");

        dataFactoryService.updatePoolItemStatus(1L, pid, item.id(), 2);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_data_pool_item WHERE id = ?", Integer.class, item.id()), "状态已更新占用中");
        PageResult<DataPoolItemInfo> inUse = dataFactoryService.listPoolItems(1L, pid, envId, null, 2, 1, 10);
        assertEquals(1, inUse.total(), "状态筛选=占用中 1 条");

        assertThrows(ServiceException.class, () -> dataFactoryService.updatePoolItemStatus(1L, pid, item.id(), 9));

        dataFactoryService.deletePoolItem(1L, pid, item.id());
        assertEquals(1L, poolCount(envId), "删除后剩 1 条");
    }


    
    @Test
    void poolKey_apiFactory_writtenToPoolName_andExposed() throws Exception {
        startOrderApi();
        long pid = createProject("P7K");
        long envId = createEnv(pid, "键名环境");
        String content = "{\"poolType\":\"订单\",\"poolKey\":\"order_no\",\"method\":\"GET\",\"url\":\""
                + orderApiBase + "/orders\",\"extractExpr\":\"$.data[*].orderNo\"}";
        dataFactoryService.run(1L, createApiFactory(pid, envId, "键名造数", content));

        PageResult<DataPoolItemInfo> page = dataFactoryService.listPoolItems(1L, pid, envId, null, null, 1, 10);
        assertEquals(2, page.total(), "入池 2 条");
        long named = page.list().stream().filter(i -> "order_no".equals(i.name())).count();
        assertEquals(1, named, "键名落首产出项一条（同键多值=索引域冲突，其余 NULL）");
        assertEquals(1, page.list().stream().filter(i -> i.name() == null).count(), "余下项 name=NULL 不可引用");
    }

    
    @Test
    void poolKey_scriptFactory_writtenToPoolName() {
        long pid = createProject("P7S");
        long envId = createEnv(pid, "脚本环境");
        String script = "# poolType=订单\n# poolKey=ticket_code\nimport json\n"
                + "print(json.dumps([\"TCK-1\", \"TCK-2\"]))";
        long fid = dataFactoryService.create(1L,
                new DataFactoryCreateInput(pid, envId, 3, "脚本键名", script, null)).id();
        dataFactoryService.run(1L, fid);
        PageResult<DataPoolItemInfo> page = dataFactoryService.listPoolItems(1L, pid, envId, null, null, 1, 10);
        assertEquals(2, page.total());
        assertTrue(page.list().stream().anyMatch(i -> "ticket_code".equals(i.name())), "脚本型键名注释入池");
    }

    
    @Test
    void poolKey_illegalGrammar_rejected400() {
        long pid = createProject("P7B");
        long envId = createEnv(pid, "非法键");
        String content = "{\"poolType\":\"订单\",\"poolKey\":\"1bad-key\",\"method\":\"GET\",\"url\":\"http://x/1\"}";
        ServiceException e = assertThrows(ServiceException.class,
                () -> createApiFactory(pid, envId, "非法键名", content));
        assertTrue(e.getMessage().contains("数据键名"), "人话点名: " + e.getMessage());
        long fid = createApiFactory(pid, envId, "合法先建",
                "{\"poolType\":\"订单\",\"method\":\"GET\",\"url\":\"http://x/1\"}");
        dataFactoryService.update(1L, fid, new DataFactoryUpdateInput(null,
                "{\"poolType\":\"订单\",\"poolKey\":\"_ok\",\"method\":\"GET\",\"url\":\"http://x/1\"}",
                null, null));
        ServiceException e3 = assertThrows(ServiceException.class, () -> dataFactoryService.update(1L, fid,
                new DataFactoryUpdateInput(null,
                        "{\"poolType\":\"订单\",\"poolKey\":\"键 名\",\"method\":\"GET\",\"url\":\"http://x/1\"}",
                        null, null)));
        assertTrue(e3.getMessage().contains("数据键名"), "人话点名: " + e3.getMessage());
        assertNotNull(e);
    }

    
    @Test
    void poolKey_duplicateInProjectEnv_rejected400() throws Exception {
        startOrderApi();
        long pid = createProject("P7D");
        long envId = createEnv(pid, "重键环境");
        String content = "{\"poolType\":\"订单\",\"poolKey\":\"order_no\",\"method\":\"GET\",\"url\":\""
                + orderApiBase + "/orders\",\"extractExpr\":\"$.data[*].orderNo\"}";
        dataFactoryService.run(1L, createApiFactory(pid, envId, "首造", content));
        ServiceException e = assertThrows(ServiceException.class,
                () -> createApiFactory(pid, envId, "撞键造数", content));
        assertTrue(e.getMessage().contains("占用"),
                "人话点名占用: " + e.getMessage());
        long envId2 = createEnvWithType(pid, "另一环境", 3);
        assertNotNull(createApiFactory(pid, envId2, "异环境同键", content));
    }

    @Test
    void pool_crossProject_isolation_and_notFound() throws Exception {
        startOrderApi();
        long pidA = createProject("PA2");
        long pidB = createProject("PB2");
        long envA = createEnv(pidA, "测试环境");
        createEnv(pidB, "测试环境");
        String content = "{\"poolType\":\"订单\",\"method\":\"GET\",\"url\":\"" + orderApiBase
                + "/orders\",\"headers\":{},\"body\":{},\"extractExpr\":\"$.data[*].orderNo\"}";
        long fid = createApiFactory(pidA, envA, "下单造数", content);
        dataFactoryService.run(1L, fid);
        DataPoolItemInfo item = dataFactoryService.listPoolItems(1L, pidA, envA, null, null, 1, 10).list().get(0);

        PageResult<DataPoolItemInfo> cross = dataFactoryService.listPoolItems(1L, pidB, null, null, null, 1, 10);
        assertEquals(0, cross.total(), "跨项目不可见");

        assertThrows(ServiceException.class, () -> dataFactoryService.updatePoolItemStatus(1L, pidB, item.id(), 1));
        assertThrows(ServiceException.class, () -> dataFactoryService.deletePoolItem(1L, pidB, item.id()));

        assertThrows(ServiceException.class, () -> dataFactoryService.updatePoolItemStatus(1L, pidA, 999999L, 2));
        assertThrows(ServiceException.class, () -> dataFactoryService.deletePoolItem(1L, pidA, 999999L));

        long envB = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? LIMIT 1", Long.class, pidB);
        assertThrows(ServiceException.class,
                () -> dataFactoryService.listPoolItems(1L, pidA, envB, null, null, 1, 10));
    }

    @Test
    void extractExpr_arrayPath_picksOrderNos() throws Exception {
        JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"code\":\"0\",\"data\":[{\"orderNo\":\"ORD-1001\"},{\"orderNo\":\"ORD-1002\"}]}");
        List<String> values = DataFactoryServiceImpl.extractValues(node, "$.data[*].orderNo");
        assertEquals(2, values.size(), "数组路径提取 2 条: " + values);
        assertEquals("ORD-1001", values.get(0));
        assertEquals("ORD-1002", values.get(1));
        JsonNode single = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"data\":{\"id\":\"ACC-9\"}}");
        assertEquals(List.of("ACC-9"), DataFactoryServiceImpl.extractValues(single, "$.data.id"));
    }


    
    @Test
    void apiFactory_genTemplate_urlResolvedToDeterministicValue() throws Exception {
        startEchoApi();
        long pid = createProject("PG1");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"证件\",\"method\":\"GET\",\"url\":\"" + echoApiBase
                + "/id/${gen.idCard(seed=7)}\",\"headers\":{},\"body\":{},\"extractExpr\":\"$.echo\"}";
        long fid = createApiFactory(pid, envId, "模板造号", content);

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);

        assertEquals(1, result.createdCount(), result.message());
        String expected = com.sixbean.testpike.common.genkit.GenKit.idCard(7L);
        PageResult<DataPoolItemInfo> pool = dataFactoryService.listPoolItems(1L, pid, envId, null, null, 1, 10);
        assertEquals(1, pool.total(), "入池 1 条");
        assertEquals(expected, pool.list().get(0).value(),
                "服务端求值=common GenKit 单源（同 seed 可复现锚）");
    }

    
    @Test
    void sqlFactory_genTemplate_resolvedInStatement() {
        long pid = createProject("PG2");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"账号\",\"sql\":\"INSERT INTO tp_project_env "
                + "(project_id, name, env_type, base_url) VALUES (" + pid
                + ", '模板环境', 3, '${gen.md5(text=abc)}') RETURNING base_url\"}";
        long fid = dataFactoryService.create(1L, new DataFactoryCreateInput(pid, envId, 2, "SQL 模板造数", content, null)).id();

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);

        assertEquals(1, result.createdCount(), result.message());
        PageResult<DataPoolItemInfo> pool = dataFactoryService.listPoolItems(1L, pid, envId, null, null, 1, 10);
        assertEquals(1, pool.total());
        assertEquals("900150983cd24fb0d6963f7d28e17f72", pool.list().get(0).value(),
                "MD5(abc) 固定值=模板求值确实作用于 SQL 正文");
    }

    
    @Test
    void apiFactory_genTemplate_unregisteredFunction_failsClosed() throws Exception {
        startEchoApi();
        long pid = createProject("PG3");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"证件\",\"method\":\"GET\",\"url\":\"" + echoApiBase
                + "/id/${gen.nope()}\",\"extractExpr\":\"$.echo\"}";
        long fid = createApiFactory(pid, envId, "坏模板造数", content);

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);

        assertEquals(3, result.status(), "未注册函数=执行失败");
        assertTrue(result.message().contains("nope"), "人话点名函数名: " + result.message());
        assertEquals(0L, poolCount(envId), "失败池零写入");
    }

    
    @Test
    void apiFactory_genTemplate_randomWithoutSeed_recordsSeedEvent() throws Exception {
        startEchoApi();
        long pid = createProject("PG4");
        long envId = createEnv(pid, "测试环境");
        String content = "{\"poolType\":\"证件\",\"method\":\"GET\",\"url\":\"" + echoApiBase
                + "/id/${gen.phone()}\",\"extractExpr\":\"$.echo\"}";
        long fid = createApiFactory(pid, envId, "随机造号", content);

        DataFactoryRunResult result = dataFactoryService.run(1L, fid);
        assertEquals(2, result.status(), result.message());

        String seeds = jdbcTemplate.queryForObject(
                "SELECT gen_seeds::text FROM tp_data_factory_run WHERE factory_id = ? ORDER BY id DESC LIMIT 1",
                String.class, fid);
        assertNotNull(seeds, "缺省 seed 的随机族调用必须留痕（U2 确定性重放）");
        assertTrue(seeds.replaceAll("\\s", "").matches("\\[\\{\\\"fn\\\":\\\"phone\\\",\\\"seed\\\":\\d+\\}\\]"),
                "事件={fn,seed} 且零实值: " + seeds);
    }

    private long poolCount(long envId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_pool_item WHERE env_id = ?", Long.class, envId);
    }

    private long runCount(long fid) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_factory_run WHERE factory_id = ?", Long.class, fid);
    }

    // ── page 14: server-side sorting over the factory window ──
    // Fixture: four factories f1..f4 created in sequence under one unique name
    // marker (suffixes -a/-m/-m/-z) with types 1/2/1/3. The two -m rows tie on
    // the name column and f1/f3 tie on type=1; the constant id DESC tie-break
    // pins both pairs (newer first) in either direction — the same rule the
    // project window pins (project list precedent). The historical default
    // (id DESC) is [f4,f3,f2,f1]. Keyword search uses the marker (name ILIKE
    // infix matching, marker must stay a prefix — lesson) so the
    // queried segment contains exactly our rows.

    private long createFactoryOf(long pid, long envId, int type, String name) {
        String content = switch (type) {
            case 2 -> "{\"poolType\":\"账号\",\"sql\":\"SELECT 1\"}";
            case 3 -> "# poolKey=sortprobe\nprint([])";
            default -> "{\"poolType\":\"订单\",\"method\":\"GET\",\"url\":\"http://x/1\"}";
        };
        return dataFactoryService.create(1L,
                new DataFactoryCreateInput(pid, envId, type, name, content, null)).id();
    }

    /** Seed f1..f4 under one unique name marker; returns their ids in creation order. */
    private long[] seedSortFixture(long pid, long envId, String marker) {
        long f1 = createFactoryOf(pid, envId, 1, marker + "-a");
        long f2 = createFactoryOf(pid, envId, 2, marker + "-m");
        long f3 = createFactoryOf(pid, envId, 1, marker + "-m");
        long f4 = createFactoryOf(pid, envId, 3, marker + "-z");
        return new long[]{f1, f2, f3, f4};
    }

    private List<Long> factoryIdsSorted(long pid, String marker, String sortBy, String sortDir,
                                        int page, int size) {
        return dataFactoryService.list(1L, new DataFactoryQuery(pid, marker, null, page, size,
                        sortBy, sortDir))
                .list().stream().map(DataFactoryInfo::id).toList();
    }

    private List<Long> idsOf(long[] seeded, int... indexes) {
        return java.util.Arrays.stream(indexes).mapToObj(i -> seeded[i]).toList();
    }

    /** No sort key keeps the historical id DESC default; the legacy five-argument
     * query delegates to the same default (zero drift). */
    @Test
    void test_sort_absent_key_keeps_id_desc_and_legacy_query_delegates() {
        long pid = createProject("SORTP1");
        long envId = createEnv(pid, "测试环境");
        String marker = "t292b5f" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(pid, envId, marker);
        List<Long> newestFirst = idsOf(seeded, 3, 2, 1, 0);
        assertEquals(newestFirst, factoryIdsSorted(pid, marker, null, null, 1, 20));
        assertEquals(newestFirst, factoryIdsSorted(pid, marker, "", "", 1, 20));
        List<Long> viaLegacy = dataFactoryService.list(1L, new DataFactoryQuery(pid, marker, null, 1, 20))
                .list().stream().map(DataFactoryInfo::id).toList();
        assertEquals(newestFirst, viaLegacy, "legacy 5-arg query must not drift");
    }

    /** name asc follows -a/-m/-z while the two tied -m rows stay newer-first
     * through the id DESC tie-break; desc pins the same pair the same way. */
    @Test
    void test_sort_name_column_tied_rows_pinned_by_id_desc() {
        long pid = createProject("SORTP2");
        long envId = createEnv(pid, "测试环境");
        String marker = "t292b5f" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(pid, envId, marker);
        assertEquals(idsOf(seeded, 0, 2, 1, 3), factoryIdsSorted(pid, marker, "name", "asc", 1, 20));
        assertEquals(idsOf(seeded, 3, 2, 1, 0), factoryIdsSorted(pid, marker, "name", "desc", 1, 20));
    }

    /** type asc groups 1/1/2/3 with the tied type-1 pair pinned newer-first;
     * desc mirrors the group order with the same tie-break. */
    @Test
    void test_sort_type_column_both_directions_with_ties_pinned() {
        long pid = createProject("SORTP3");
        long envId = createEnv(pid, "测试环境");
        String marker = "t292b5f" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(pid, envId, marker);
        assertEquals(idsOf(seeded, 2, 0, 1, 3), factoryIdsSorted(pid, marker, "type", "asc", 1, 20));
        assertEquals(idsOf(seeded, 3, 1, 2, 0), factoryIdsSorted(pid, marker, "type", "desc", 1, 20));
    }

    /** id desc equals the historical default; sorting keeps paging consistent:
     * page 2 continues the ordered window and total stays truthful. */
    @Test
    void test_sort_applies_across_pages() {
        long pid = createProject("SORTP4");
        long envId = createEnv(pid, "测试环境");
        String marker = "t292b5f" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(pid, envId, marker);
        assertEquals(idsOf(seeded, 3, 2, 1, 0), factoryIdsSorted(pid, marker, "id", "desc", 1, 20));
        PageResult<DataFactoryInfo> first = dataFactoryService.list(1L,
                new DataFactoryQuery(pid, marker, null, 1, 2, "type", "asc"));
        PageResult<DataFactoryInfo> second = dataFactoryService.list(1L,
                new DataFactoryQuery(pid, marker, null, 2, 2, "type", "asc"));
        assertEquals(idsOf(seeded, 2, 0), first.list().stream().map(DataFactoryInfo::id).toList());
        assertEquals(idsOf(seeded, 1, 3), second.list().stream().map(DataFactoryInfo::id).toList());
        assertEquals(4L, first.total());
    }

    /** An injection-shaped or off-list key is refused fail-closed with a human
     * 400 naming the whitelist; rows stay intact afterwards. */
    @Test
    void test_sort_off_list_key_refused_with_whitelist_hint() {
        long pid = createProject("SORTP5");
        long envId = createEnv(pid, "测试环境");
        String marker = "t292b5f" + java.util.UUID.randomUUID().toString().substring(0, 8);
        seedSortFixture(pid, envId, marker);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> factoryIdsSorted(pid, marker, "created_at; DROP TABLE tp_data_factory", "asc", 1, 20));
        assertEquals(com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR,
                rejected.getCode());
        assertTrue(rejected.getMessage().contains("created_at"),
                "error should name the allowed columns, actual=" + rejected.getMessage());
        assertEquals(4, factoryIdsSorted(pid, marker, null, null, 1, 20).size(), "rows must be unaffected");
    }
}
