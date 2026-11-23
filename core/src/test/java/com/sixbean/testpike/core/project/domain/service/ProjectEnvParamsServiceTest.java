package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.project.api.dto.EnvParamItem;
import com.sixbean.testpike.core.project.api.dto.EnvParamsInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.component.EnvParamCipherSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ProjectEnvParamsServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService envService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCipher configCipher;

    private long projectId;
    private long envId;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Creates a fixture project through the service path; sweeps a
     * crashed run's same-code leftover first, tracked for teardown. */
    private long createProject(String name, String code, String pool) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(5L, new ProjectCreateInput(name, code,
                "zh-CN", pool, null));
        projectIds.add(id);
        return id;
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    private void initEnv() {
        projectId = createProject("参数项目", "PARAMS", "P1");
        envId = envService.create(projectId, new ProjectEnvInput("dev", 1, "http://dev.example.com",
                "{\"loginUrl\":\"/login\"}", "web", "chromium", null));
    }

    private String storedConfigJson() {
        return jdbcTemplate.queryForObject("SELECT config_json::text FROM tp_project_env WHERE id = ?",
                String.class, envId);
    }

    @Test
    void saveParams_全链_DB密文无明文_读取打码() {
        Assumptions.assumeTrue(configCipher.isEnabled(), "密文形态钉需 CONFIG_AES_KEY（.env 注入）");
        initEnv();
        List<EnvParamItem> saved = envService.saveParams(projectId, envId,
                new EnvParamsInput(Map.of("ACCOUNT", "qa_user", "PASSWORD", "p@ss!w0rd")));
        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(i -> ConfigCipher.MASK.equals(i.value()) && i.encrypted()),
                "写后立即读=全打码（永不回传实值）");

        String stored = storedConfigJson();
        assertTrue(stored.contains(EnvParamCipherSupport.ENC_PREFIX), "DB 侧值为 ENC: 密文");
        assertTrue(!stored.contains("p@ss!w0rd") && !stored.contains("qa_user"),
                "DB config_json 零明文（IT-K8-02 同族）");
        assertTrue(stored.contains("loginUrl"), "非 params 键（loginUrl）保留不被覆盖");
    }

    @Test
    void saveParams_非法键名_400人话() {
        initEnv();
        ServiceException ex = assertThrows(ServiceException.class, () -> envService.saveParams(
                projectId, envId, new EnvParamsInput(Map.of("{{INJECT}}", "v"))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("{{INJECT}}"), "点名违规键，当前=" + ex.getMessage());
    }

    @Test
    void saveParams_环境不存在_404人话() {
        initEnv();
        ServiceException ex = assertThrows(ServiceException.class, () -> envService.saveParams(
                projectId, 999999L, new EnvParamsInput(Map.of("K", "v"))));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void saveParams_跨项目环境_404人话_H1隔离() {
        initEnv();
        long otherProject = createProject("他项目", "OTHER", "P2");
        assertThrows(ServiceException.class, () -> envService.listParams(otherProject, envId));
    }

    @Test
    void listParams_无参数_空列表() {
        initEnv();
        assertTrue(envService.listParams(projectId, envId).isEmpty());
    }

    @Test
    void update_裸configJson改params段_400守卫拦截() {
        Assumptions.assumeTrue(configCipher.isEnabled(), "构造 ENC: 密文段需密钥");
        initEnv();
        envService.saveParams(projectId, envId, new EnvParamsInput(Map.of("KEEP", "v1")));
        ServiceException ex = assertThrows(ServiceException.class, () -> envService.update(
                projectId, envId, new ProjectEnvInput("dev", 1, "http://dev.example.com",
                "{\"loginUrl\":\"/login\"}", "web", "chromium", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(),
                "update 须接线 assertParamsSectionUnchanged 守卫（tick 138 坑 e），当前=" + ex.getMessage());
    }

    @Test
    void update_回传原configJson_放行不误伤() {
        initEnv();
        String current = storedConfigJson();
        envService.update(projectId, envId, new ProjectEnvInput("dev2", 1, "http://dev.example.com",
                current, "web", "chromium", null));
        assertEquals("dev2", envService.get(projectId, envId).name());
    }
}
