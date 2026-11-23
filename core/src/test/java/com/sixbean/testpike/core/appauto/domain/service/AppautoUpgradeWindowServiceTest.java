package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoUpgradeWindowView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoUpgradeWindowServiceTest {

    @Autowired
    private AppautoUpgradeWindowService windowService;
    @Autowired
    private ConfigCacheHolder configCacheHolder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "win" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "排窗测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        configCacheHolder.put(AppautoConfigReader.KEY_UPGRADE_WINDOW, "1");
    }

    @AfterEach
    void tearDown() {
        configCacheHolder.remove(AppautoConfigReader.KEY_UPGRADE_WINDOW);
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan_step WHERE plan_id IN "
                + "(SELECT id FROM tp_upgrade_plan WHERE operator LIKE 'win-test-%')");
        jdbcTemplate.update("DELETE FROM tp_upgrade_plan WHERE operator LIKE 'win-test-%'");
        jdbcTemplate.update("DELETE FROM tp_exec_batch WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void seedBatch(int startedHoursAgo, int runningHours) {
        jdbcTemplate.update("""
                INSERT INTO tp_exec_batch (project_id, name, status, type, started_at, finished_at)
                VALUES (?, ?, 6, 1,
                        CURRENT_TIMESTAMP - (? || ' hours')::interval,
                        CURRENT_TIMESTAMP - (? || ' hours')::interval)
                """, projectId, "win-" + System.nanoTime(),
                String.valueOf(startedHoursAgo), String.valueOf(startedHoursAgo - runningHours));
    }

    private void seedPlan(String status) {
        jdbcTemplate.update("INSERT INTO tp_upgrade_plan (scope, status, operator) "
                + "VALUES ('engine', ?, ?)", status, "win-test-" + System.nanoTime());
    }

    @Test
    void switchOffRejectsHumanWords() {
        configCacheHolder.put(AppautoConfigReader.KEY_UPGRADE_WINDOW, "0"); // seed default flipped to '1' by the AI default-on batch; force off-state explicitly
        ServiceException ex = assertThrows(ServiceException.class, windowService::recommend);
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("未开启：请在「系统管理 → 系统配置」搜索") && ex.getMessage().contains("ai.appauto.upgrade-window"), "关态文案须人话：" + ex.getMessage());
    }

    @Test
    void runningPlanIsRedLineRefusal() {
        seedPlan("RUNNING");
        ServiceException ex = assertThrows(ServiceException.class, windowService::recommend);
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("在跑"), "冲突拒答须点名在跑计划：" + ex.getMessage());
    }

    @Test
    void recommendsLeastBusySlotsWithBusyCurve() {
        seedBatch(8, 6);
        List<AppautoUpgradeWindowView> windows = windowService.recommend();
        assertFalse(windows.isEmpty(), "无冲突时必须给出逐时曲线");
        assertTrue(windows.stream().anyMatch(w -> w.busyBatches() > 0),
                "重叠批次必须体现在忙闲曲线：\n" + windows);
        assertTrue(windows.stream().anyMatch(w -> w.recommended()),
                "存在全空闲槽时应给出推荐位：\n" + windows);
        assertTrue(windows.stream().noneMatch(w -> w.recommended() && w.busyBatches() > 0),
                "推荐槽位禁落在有批次重叠的忙槽");
        assertFalse(windows.get(0).canaryHint().isBlank(), "灰度切法建议随窗同行（只推荐不落权）");
    }

    @Test
    void emptyClusterStillAnswersHonestCurve() {
        List<AppautoUpgradeWindowView> windows = windowService.recommend();
        assertEquals(24, windows.size(), "缺省 K8 键=24 槽曲线");
        assertTrue(windows.stream().allMatch(w -> w.busyBatches() == 0));
    }
}
