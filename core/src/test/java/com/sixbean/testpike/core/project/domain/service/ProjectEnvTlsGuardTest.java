package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ProjectEnvTlsGuardTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService envService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Creates a fixture project through the service path; sweeps a
     * crashed run's same-code leftover first, tracked for teardown. */
    private long createProject(String name, String code) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(5L, new ProjectCreateInput(name, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    private void initProject() {
        projectId = createProject("TLS项目", "TLSP");
    }

    private ProjectEnvInput envInput(int envType, String configJson) {
        return new ProjectEnvInput("测试环境", envType, "https://intranet.example.com",
                configJson, "web", "chromium", null);
    }

    @Test
    void create_生产环境带insecure段_保存即拦400人话() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class, () -> envService.create(projectId,
                envInput(4, "{\"tls\":{\"mode\":\"insecure\"}}")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("生产"), "红字点名生产段，当前=" + ex.getMessage());
    }

    @Test
    void update_切生产段后带insecure_同样拦() {
        initProject();
        long envId = envService.create(projectId, envInput(2, "{\"tls\":{\"mode\":\"insecure\"}}"));
        ServiceException ex = assertThrows(ServiceException.class, () -> envService.update(projectId, envId,
                envInput(4, "{\"tls\":{\"mode\":\"insecure\"}}")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void create_非生产insecure_正常落库() {
        initProject();
        long envId = envService.create(projectId, envInput(2, "{\"tls\":{\"mode\":\"insecure\"}}"));
        String stored = jdbcTemplate.queryForObject(
                "SELECT config_json::text FROM tp_project_env WHERE id = ?", String.class, envId);
        assertTrue(stored.contains("insecure"));
    }

    @Test
    void update_裸configJson改tls段_守卫拦截() {
        initProject();
        long envId = envService.create(projectId, envInput(2, "{\"tls\":{\"mode\":\"strict\"}}"));
        ServiceException ex = assertThrows(ServiceException.class, () -> envService.update(projectId, envId,
                envInput(2, "{\"tls\":{\"mode\":\"custom_ca\"}}")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("tls") || ex.getMessage().contains("证书") || ex.getMessage().contains("TLS"),
                "指引语点名 TLS，当前=" + ex.getMessage());
    }

    @Test
    void update_回传原configJson_不误伤() {
        initProject();
        long envId = envService.create(projectId, envInput(2, "{\"tls\":{\"mode\":\"strict\"},\"loginUrl\":\"/login\"}"));
        envService.update(projectId, envId, envInput(2, "{\"tls\":{\"mode\":\"strict\"},\"loginUrl\":\"/login\"}"));
        envService.update(projectId, envId, envInput(2, "{\"loginUrl\":\"/login\"}"));
    }
}
