package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineAvailabilityInfo;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = com.sixbean.testpike.core.CoreApplication.class)
class AppautoAvailabilityServiceTest {

    @Autowired
    private AppautoAvailabilityService availabilityService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (shared-dev-db tranche): removes only this suite's
    // executor family; the zero-online assertions rely on the window recipe
    // quiescing the resident chain runner before the suite starts (see PROGRESS).
    @BeforeEach
    @AfterEach
    void cleanExecutors() {
        AppautoExecutorFixture.deleteExecutorFamily(jdbcTemplate, "avail-ex-");
    }

    private long registerWithEngines(String name, List<String> engines) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web,app", "chromium", 2, "1.0.0", 0,
                Map.of("engines", engines, "platforms", List.of("ANDROID"))));
    }

    private AppautoEngineAvailabilityInfo find(List<AppautoEngineAvailabilityInfo> rows, String code) {
        return rows.stream().filter(r -> code.equals(r.code())).findFirst().orElse(null);
    }

    @Test
    void online_executor_reporting_engine_makes_it_available_with_counted_executors() {
        registerWithEngines("avail-ex-1", List.of("MOBILEWRIGHT", "MAESTRO"));
        registerWithEngines("avail-ex-2", List.of("MOBILEWRIGHT"));
        List<AppautoEngineAvailabilityInfo> rows = availabilityService.availability(false);
        AppautoEngineAvailabilityInfo mobilewright = find(rows, "MOBILEWRIGHT");
        assertNotNull(mobilewright, "启用面必须含默认引擎 Mobilewright");
        assertTrue(mobilewright.available(), "两台在线执行器均上报=可用");
        assertEquals(2, mobilewright.onlineCount(), "onlineCount=上报该引擎的在线台数");
        assertEquals(1, find(rows, "MAESTRO").onlineCount());
        assertTrue(mobilewright.missingReasons().isEmpty(), "可用引擎不得挂缺失原因");
    }

    @Test
    void engine_nobody_reports_is_unavailable_with_plain_language_reason() {
        registerWithEngines("avail-ex-3", List.of("MOBILEWRIGHT"));
        List<AppautoEngineAvailabilityInfo> rows = availabilityService.availability(false);
        AppautoEngineAvailabilityInfo appium = find(rows, "APPIUM");
        assertNotNull(appium, "APPIUM 应在启用面（描述符启用但本地无人装）");
        assertFalse(appium.available());
        assertEquals(0, appium.onlineCount());
        assertTrue(appium.missingReasons().stream().anyMatch(s -> s.contains("执行器")),
                "缺失原因必须人话点名执行器安装面: " + appium.missingReasons());
    }

    @Test
    void zero_online_executors_reason_names_no_executor_online() {
        List<AppautoEngineAvailabilityInfo> rows = availabilityService.availability(false);
        assertFalse(rows.isEmpty(), "目录启用面与执行器在线面解耦——零在线仍出全引擎清单");
        assertTrue(rows.stream().noneMatch(AppautoEngineAvailabilityInfo::available));
        AppautoEngineAvailabilityInfo first = rows.get(0);
        assertTrue(first.missingReasons().stream().anyMatch(s -> s.contains("无在线执行器")),
                "零在线=原因必须点名「当前无在线执行器」，禁混入「未上报」口径: "
                        + first.missingReasons());
    }

    @Test
    void disabled_descriptor_only_shows_in_full_face_and_unavailable() {
        registerWithEngines("avail-ex-4", List.of("MOBILEWRIGHT"));
        assertFalse(availabilityService.availability(false).stream()
                .anyMatch(r -> "HYPIUM".equals(r.code())), "停用候补位不得混入用户可选面");
        AppautoEngineAvailabilityInfo hypium = find(availabilityService.availability(true), "HYPIUM");
        assertNotNull(hypium, "管理面含停用候补位");
        assertFalse(hypium.available());
        assertTrue(hypium.missingReasons().stream().anyMatch(s -> s.contains("停用") || s.contains("下架")),
                "原因必须点名上下架状态: " + hypium.missingReasons());
    }
}
