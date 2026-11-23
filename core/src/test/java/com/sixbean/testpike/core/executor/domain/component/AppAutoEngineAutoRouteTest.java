package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class AppAutoEngineAutoRouteTest {

    @Autowired
    private AppAutoDispatchSupport appAutoDispatchSupport;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private final String executorNamePrefix = "route-ex-%";

    @BeforeEach
    void setUp() {
        String code = "route" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "自动路由测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        cleanExecutors();
    }

    @AfterEach
    void tearDown() {
        cleanExecutors();
        jdbcTemplate.update("DELETE FROM tp_appauto_env_profile WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void cleanExecutors() {
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE ?", executorNamePrefix);
    }

    
    private void registerExecutor(String name, List<String> engines,
                                  List<String> platforms, List<String> deviceKinds) {
        executorService.register(new ExecutorRegisterInput(
                name, null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", engines, "platforms", platforms,
                        "deviceKinds", deviceKinds)));
    }

    private List<Map<String, Object>> launchSteps() {
        return List.of(Map.of("action", "app.launch", "target", Map.of("pkg", "com.demo")));
    }

    @SuppressWarnings("unchecked")
    private String routedEngineCode(Map<String, Object> envelope) {
        return String.valueOf(((Map<String, Object>) envelope.get("engine")).get("code"));
    }


    @Test
    void autoRoute_harmonyTarget_nullEngine_routesToCapableHarmonyEngine() {
        registerExecutor("route-ex-hm-" + System.nanoTime(),
                List.of("HMNEXTAUTO"), List.of("HARMONY_NEXT"), List.of("REAL"));
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, null, "harmony", null, launchSteps());
        assertEquals("HMNEXTAUTO", routedEngineCode(envelope),
                "harmony 目标自动路由须落在平台覆盖对账绿的引擎（禁盲发不含鸿蒙面的默认引擎）");
    }


    @Test
    void autoRoute_mpTarget_skipsGrayAndNonCovering_picksOnlineCapable() {
        registerExecutor("route-ex-minium-" + System.nanoTime(),
                List.of("MINIUM"), List.of("MP"), List.of("EMULATOR"));
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, null, "mp", null, launchSteps());
        assertEquals("MINIUM", routedEngineCode(envelope),
                "自动路由=平台覆盖 ∧ 在线绿灯优先，非盲取 K8 默认（默认位不覆盖/灰=跳过降推荐序）");
    }


    @Test
    void autoRoute_appTarget_bothGreen_prefersLowerDefaultPriority() {
        registerExecutor("route-ex-dual-" + System.nanoTime(),
                List.of("MOBILEWRIGHT", "MAESTRO"),
                List.of("ANDROID", "IOS"), List.of("EMULATOR", "REAL"));
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, null, "app", null, launchSteps());
        assertEquals("MOBILEWRIGHT", routedEngineCode(envelope),
                "双绿按引擎目录 defaultPriority 推荐序（10<20），与可用性灯/默认引擎置顶同源");
    }


    @Test
    void autoRoute_allGrayForTarget_fallsBackToK8DefaultEngine() {
        registerExecutor("route-ex-androidonly-" + System.nanoTime(),
                List.of("MOBILEWRIGHT"), List.of("ANDROID"), List.of("EMULATOR"));
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, null, "harmony", null, launchSteps());
        assertEquals("MOBILEWRIGHT", routedEngineCode(envelope),
                "全灰=K8 默认引擎兜底（禁拒单禁猜第三台；可执行性由建单健康闸/排队位人话呈现）");
    }


    @Test
    void autoRoute_explicitEngine_bypassesRouting() {
        registerExecutor("route-ex-hm2-" + System.nanoTime(),
                List.of("HMNEXTAUTO"), List.of("HARMONY_NEXT"), List.of("REAL"));
        Map<String, Object> envelope = appAutoDispatchSupport.buildEnvelope(
                projectId, "MAESTRO", "harmony", null, launchSteps());
        assertEquals("MAESTRO", routedEngineCode(envelope),
                "显式给出引擎=路由零参与（批次/单次一键改选口径）");
    }


    @Test
    void autoRoute_noTarget_keepsK8DefaultSinglePoint() {
        registerExecutor("route-ex-mw-" + System.nanoTime(),
                List.of("MOBILEWRIGHT"), List.of("ANDROID"), List.of("EMULATOR"));
        Map<String, Object> legacy = appAutoDispatchSupport.buildEnvelope(
                projectId, null, null, launchSteps());
        assertEquals("MOBILEWRIGHT", routedEngineCode(legacy),
                "旧 4 参装配位零冲击（清理机/探索富化等无目标语境=默认引擎单点）");
        Map<String, Object> webTarget = appAutoDispatchSupport.buildEnvelope(
                projectId, null, "web", null, launchSteps());
        assertEquals("MOBILEWRIGHT", routedEngineCode(webTarget),
                "web 目标无平台面=同落默认单点（web 建单本不走 APP 信封，此钉防御语义）");
    }
}
