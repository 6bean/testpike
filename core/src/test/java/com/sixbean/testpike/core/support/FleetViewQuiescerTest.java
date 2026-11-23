package com.sixbean.testpike.core.support;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.api.dto.ExecutorInfo;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two-way fixture for {@link FleetViewQuiescer}: the exclusion must actually
 * drop the aged executor from the recomputed online view, and the restore
 * must bring it back. Negative direction first (a no-op UPDATE would pass a
 * positive-only test silently), positive direction second — a gate that never
 * turns red is not a gate.
 * <p>Real chain on the dev DB, no mocks. Fixture rows live in the
 * 'quiesce-fv-%' naming family and are swept by that prefix.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class FleetViewQuiescerTest {

    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtureFamily() {
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'quiesce-fv-%'");
    }

    private void register(String name) {
        executorService.register(new ExecutorRegisterInput(
                name, null, null, "web,perf", "chromium", 1, "1.0.0", 0,
                java.util.Map.of("engines", List.of("K6"))));
    }

    private boolean online(String name) {
        return executorService.listOnline().stream()
                .map(ExecutorInfo::name)
                .anyMatch(name::equals);
    }

    @Test
    void quiesce_excludes_non_kept_executors_from_online_view_and_restore_rejoins_them() {
        register("quiesce-fv-keep");
        register("quiesce-fv-other");
        assertTrue(online("quiesce-fv-other"), "fixture precondition: both nodes online");

        List<Long> aged = FleetViewQuiescer.quiesceOthers(jdbcTemplate, "quiesce-fv-keep%");
        try {
            assertFalse(online("quiesce-fv-other"),
                    "被置旧心跳的执行器必须掉出在线视图（负方向夹具）");
            assertTrue(online("quiesce-fv-keep"),
                    "keep 模式命中的执行器必须留在在线视图（选择性）");
            Long otherId = jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_executor WHERE name = 'quiesce-fv-other'", Long.class);
            assertTrue(aged.contains(otherId), "quiesceOthers 必须返回被置旧行的 id 清单");
        } finally {
            FleetViewQuiescer.restore(jdbcTemplate, aged);
        }

        assertTrue(online("quiesce-fv-other"), "复原后必须重新进入在线视图（正方向夹具）");
    }

    @Test
    void restore_of_empty_id_list_is_an_idempotent_noop() {
        register("quiesce-fv-solo");
        FleetViewQuiescer.restore(jdbcTemplate, List.of());
        assertTrue(online("quiesce-fv-solo"), "空清单复原=无操作，不得误伤在线面");
    }
}
