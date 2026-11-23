package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.monitor.api.dto.MonitorLinkedAssetsView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class MonitorLinkedAssetsTest {

    @Autowired
    private MonitorLinkedAssetService monitorLinkedAssetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;
    private long otherEnvId;

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_apitest_run WHERE request_id IN "
                + "(SELECT r.id FROM tp_apitest_request r JOIN tp_apitest_collection c ON c.id = r.collection_id "
                + "WHERE c.project_id IN (SELECT id FROM tp_project WHERE name LIKE 't274p4d%'))");
        jdbcTemplate.update("DELETE FROM tp_apitest_request WHERE collection_id IN "
                + "(SELECT id FROM tp_apitest_collection WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4d%'))");
        jdbcTemplate.update("DELETE FROM tp_apitest_collection WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4d%')");
        jdbcTemplate.update("DELETE FROM tp_data_factory WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4d%')");
        jdbcTemplate.update("DELETE FROM tp_regression_suite WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4d%')");
        jdbcTemplate.update("DELETE FROM tp_exec_plan WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4d%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274p4d%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274p4d%'");
    }

    @BeforeEach
    void seedTopology() {
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code) VALUES ('t274p4d项目', substr(md5(random()::text), 1, 16)) "
                        + "RETURNING id", Long.class);
        envId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4d环境', 1, 'http://127.0.0.1:1') RETURNING id", Long.class, projectId);
        otherEnvId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                        + "VALUES (?, 't274p4d他环境', 2, 'http://127.0.0.1:2') RETURNING id", Long.class, projectId);
    }

    private long insertPlan(String name, String envIdsJson) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_plan (project_id, name, env_ids_json) VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, name, envIdsJson);
    }

    private long insertSuite(String name, String envIdsJson) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_regression_suite (project_id, name, env_ids_json) VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, name, envIdsJson);
    }

    private long insertFactory(String name, long env) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_data_factory (project_id, env_id, type, name, content) "
                        + "VALUES (?, ?, 1, ?, 'print(1)') RETURNING id", Long.class, projectId, env, name);
    }

    
    private long insertRun(String requestName, long env) {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_apitest_collection (project_id, name) VALUES (?, ?) RETURNING id",
                Long.class, projectId, "t274p4d集合-" + requestName);
        long requestId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_apitest_request (collection_id, name, method, url_path) "
                        + "VALUES (?, ?, 'GET', '/ping') RETURNING id", Long.class, collectionId, requestName);
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, env_id, status) VALUES (?, ?, 1)",
                requestId, env);
        return requestId;
    }


    @Test
    void 四路资产各按本环境引用计数并带名称() {
        insertPlan("计划A", "[" + envId + "]");
        insertPlan("计划B", "[" + otherEnvId + "," + envId + "]");
        insertSuite("套件A", "[" + envId + "]");
        insertFactory("造数A", envId);
        insertRun("登录探测请求", envId);

        MonitorLinkedAssetsView v = monitorLinkedAssetService.linkedAssets(envId);
        assertEquals(envId, v.envId());
        assertEquals(2, v.planCount(), "env_ids_json 含本环境的计划数");
        assertTrue(v.planNames().contains("计划A") && v.planNames().contains("计划B"));
        assertEquals(1, v.suiteCount());
        assertTrue(v.suiteNames().contains("套件A"));
        assertEquals(1, v.factoryCount());
        assertTrue(v.factoryNames().contains("造数A"));
        assertEquals(1, v.apiRequestCount());
        assertTrue(v.apiRequestNames().contains("登录探测请求"));
    }


    @Test
    void 引用他环境的资产不计入本环境影响面() {
        insertPlan("他环境计划", "[" + otherEnvId + "]");
        insertSuite("他环境套件", "[" + otherEnvId + "]");
        insertFactory("他环境造数", otherEnvId);
        insertRun("他环境请求", otherEnvId);

        MonitorLinkedAssetsView v = monitorLinkedAssetService.linkedAssets(envId);
        assertEquals(0, v.planCount());
        assertEquals(0, v.suiteCount());
        assertEquals(0, v.factoryCount());
        assertEquals(0, v.apiRequestCount());
    }


    @Test
    void 脏数据免疫_空串非法文本非数组非法元素不炸不误计() {
        insertPlan("空串计划", "");
        insertPlan("非法计划", "not-a-json");
        insertPlan("非数组计划", "{\"a\":1}");
        insertPlan("混脏元素计划", "[" + otherEnvId + ",\"abc\",null," + envId + "]");

        MonitorLinkedAssetsView v = monitorLinkedAssetService.linkedAssets(envId);
        assertEquals(1, v.planCount(), "仅数字命中元素计数，脏元素/脏文本免疫不炸");
        assertTrue(v.planNames().contains("混脏元素计划"));
    }


    @Test
    void 重复元素与重复执行同资产只计一次() {
        insertPlan("重复元素计划", "[" + envId + "," + envId + "]");
        long request = insertRun("重复执行请求", envId);
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, env_id, status) VALUES (?, ?, 1)",
                request, envId);
        jdbcTemplate.update("INSERT INTO tp_apitest_run (request_id, env_id, status) VALUES (?, ?, 1)",
                request, otherEnvId);

        MonitorLinkedAssetsView v = monitorLinkedAssetService.linkedAssets(envId);
        assertEquals(1, v.planCount(), "同计划 env_ids_json 重复列本环境只计一次");
        assertEquals(1, v.apiRequestCount(), "同请求多次执行只计一个资产，他环境执行不另计");
    }


    @Test
    void 未知环境查询返回全零不抛异常() {
        MonitorLinkedAssetsView v = monitorLinkedAssetService.linkedAssets(999999999L);
        assertEquals(0, v.planCount());
        assertEquals(0, v.suiteCount());
        assertEquals(0, v.factoryCount());
        assertEquals(0, v.apiRequestCount());
    }
}
