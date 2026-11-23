package com.sixbean.testpike.core.explore.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.explore.api.dto.ExploreActInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.api.controller.ExploreInternalController;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoExploreAccessGateTest {

    private static final String APP_URL = "app://com.demo.shop";
    private static final String MOBILE_ACT_JSON =
            "{\"kind\":\"explore-act\",\"target\":\"mobile\",\"action\":\"tree-snapshot\","
                    + "\"engine\":{\"id\":\"mobilewright\",\"driver\":\"adb\"},"
                    + "\"leaseRef\":{\"serial\":\"emulator-5554\"}}";

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ExploreInternalController exploreController;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long testEnvId;
    private long prodEnvId;

    @BeforeEach
    void seedFixtures() {
        // Fixture-scoped teardown (R37): sweep this suite's own project
        // subtrees (EXPGATE + cross-project fixture) — a blanket
        // TRUNCATE tp_project CASCADE wipes concurrent chains' rows (r13).
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXPGATE"),
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, "EXPGATE-OTH"));
        configCacheHolder.put("ai.explore.appauto", "false");
        projectId = projectService.create(1L,
                new ProjectCreateInput("探索闸测试项目", "EXPGATE", "zh-CN", "P1", null));
        testEnvId = insertEnv("测试-探索闸", 2);
        prodEnvId = insertEnv("生产-探索闸", 4);
    }

    @AfterEach
    void restoreSeedState() {
        configCacheHolder.put("ai.explore.appauto", "false");
    }

    private long insertEnv(String name, int envType) {
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, ?, ?, 'http://env.local/api')", projectId, name, envType);
        return jdbcTemplate.queryForObject("SELECT max(id) FROM tp_project_env", Long.class);
    }

    private ExploreCreateInput mobileInput(Long envId) {
        return new ExploreCreateInput("摸清下单流程", APP_URL, envId);
    }

    
    @Test
    void 开关关_移动create拒40301人话_零残留() {
        ServiceException denied = assertThrows(ServiceException.class,
                () -> exploreService.create(1L, projectId, mobileInput(testEnvId)));
        assertEquals(40301, denied.getCode().intValue(), "关态拒=FORBIDDEN: " + denied.getMessage());
        assertTrue(denied.getMessage().contains("ai.explore.appauto"), "拒止文案点名配置键: " + denied.getMessage());
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_explore_session WHERE project_id = ?", Long.class, projectId);
        assertEquals(0L, rows, "关态零残留（冻结 §14.3 硬闸）");
    }

    
    @Test
    void 开关关_移动estimate同闸拒40301() {
        ServiceException denied = assertThrows(ServiceException.class,
                () -> exploreService.estimate(projectId, mobileInput(testEnvId)));
        assertEquals(40301, denied.getCode().intValue());
    }

    
    @Test
    void 开关开_移动形态缺环境申报拒40001() {
        configCacheHolder.put("ai.explore.appauto", "true");
        ServiceException missing = assertThrows(ServiceException.class,
                () -> exploreService.create(1L, projectId, mobileInput(null)));
        assertEquals(40001, missing.getCode().intValue());
        assertTrue(missing.getMessage().contains("环境"), "人话点名环境申报: " + missing.getMessage());
    }

    
    @Test
    void 开关开_生产环境探索拒40301点名生产() {
        configCacheHolder.put("ai.explore.appauto", "true");
        ServiceException denied = assertThrows(ServiceException.class,
                () -> exploreService.create(1L, projectId, mobileInput(prodEnvId)));
        assertEquals(40301, denied.getCode().intValue());
        assertTrue(denied.getMessage().contains("生产"), "人话点名生产禁入: " + denied.getMessage());
    }

    
    @Test
    void 开关开_预发环境缺位即拒40301() {
        configCacheHolder.put("ai.explore.appauto", "true");
        long preEnvId = insertEnv("预发-探索闸", 3);
        ServiceException denied = assertThrows(ServiceException.class,
                () -> exploreService.create(1L, projectId, mobileInput(preEnvId)));
        assertEquals(40301, denied.getCode().intValue(), "白名单仅开发/测试，预发缺位即拒: " + denied.getMessage());
    }

    
    @Test
    void 开关开_环境归属越界拒() {
        configCacheHolder.put("ai.explore.appauto", "true");
        long otherProject = projectService.create(1L,
                new ProjectCreateInput("探索闸他项目", "EXPGATE-OTH", "zh-CN", "P1", null));
        ServiceException denied = assertThrows(ServiceException.class,
                () -> exploreService.create(1L, otherProject, mobileInput(testEnvId)));
        assertTrue(denied.getMessage().contains("不属于"), "人话点名归属越界: " + denied.getMessage());
    }

    
    @Test
    void 开关开_测试环境移动create成功_落app语义零SSRF误伤() {
        configCacheHolder.put("ai.explore.appauto", "true");
        long sessionId = exploreService.create(1L, projectId, mobileInput(testEnvId));
        assertTrue(sessionId > 0);
        String stored = jdbcTemplate.queryForObject(
                "SELECT start_url FROM tp_explore_session WHERE id = ?", String.class, sessionId);
        assertEquals(APP_URL, stored, "app://{包名} 语义原样落列（冻结定案：复用 start_url 不加列）");
    }

    
    @Test
    void submitAct_开关翻关_移动信封拒40301零任务行() {
        configCacheHolder.put("ai.explore.appauto", "true");
        long sessionId = exploreService.create(1L, projectId, mobileInput(testEnvId));
        configCacheHolder.put("ai.explore.appauto", "false");

        ServiceException denied = assertThrows(ServiceException.class,
                () -> exploreController.submitAct(sessionId, new ExploreActInput(MOBILE_ACT_JSON)));
        assertEquals(40301, denied.getCode().intValue(), "开关拒先于形状闸: " + denied.getMessage());
        Long tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Long.class, projectId);
        assertEquals(0L, tasks, "翻关后零任务残留");
    }

    
    @Test
    void 开关关_web形态零冲击照常创建() {
        long sessionId = exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清首页", "http://localhost:5173"));
        assertTrue(sessionId > 0);
        assertNotNull(exploreService.get(sessionId));
    }

    
    @Test
    void 开关开_app前缀空包名拒40001() {
        configCacheHolder.put("ai.explore.appauto", "true");
        ServiceException bad = assertThrows(ServiceException.class,
                () -> exploreService.create(1L, projectId,
                        new ExploreCreateInput("目标", "app://", testEnvId)));
        assertEquals(40001, bad.getCode().intValue());
        assertTrue(bad.getMessage().contains("包名"), "人话点名包名: " + bad.getMessage());
    }
}
