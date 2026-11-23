package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ProjectEnvServiceTest {

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
        projectId = createProject("商品中台", "PSHOP");
    }

    private ProjectEnvInput envInput(String name, int envType) {
        return new ProjectEnvInput(name, envType,
                envType == 4 ? "https://shop.example.com" : "http://dev.example.com",
                "{\"account\":\"{{ENV:ACCOUNT}}\"}", "web", "chromium,firefox", "v1.2.3");
    }

    @Test
    void create_开发环境成功() {
        initProject();
        long envId = envService.create(projectId, envInput("dev", 1));

        List<ProjectEnvInfo> list = envService.list(projectId);
        assertEquals(1, list.size());
        ProjectEnvInfo info = list.get(0);
        assertEquals(envId, info.id());
        assertEquals(1, info.envType(), "开发环境标记（v1.3：1=开发 2=测试 3=预发 4=生产）");
        assertEquals("chromium,firefox", info.browsers());
        assertEquals("web", info.endTypes());
    }

    @Test
    void create_生产环境必须填baseUrl_参数校验() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class, () -> envService.create(
                projectId, new ProjectEnvInput("prod", 4, "  ", "{}", "web", "chromium", null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "生产环境 base_url 必填（执行前黑名单校验目标）");
    }

    @Test
    void create_非法envType_参数校验() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.create(projectId, envInput("bad", 9)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void create_同名环境冲突_409() {
        initProject();
        envService.create(projectId, envInput("test", 1));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.create(projectId, envInput("test", 2)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode(), "同项目环境名唯一（uq_project_env_name）");
        assertTrue(ex.getMessage().contains("test"), "同名提示应含环境名（ServiceException 不格式化 %s，用拼接）");
    }

    @Test
    void update_修改生效() {
        initProject();
        long envId = envService.create(projectId, envInput("dev", 1));

        ProjectEnvInfo updated = envService.update(projectId, envId,
                new ProjectEnvInput("staging", 1, "http://staging.example.com", "{}", "web,mobile", "chromium", "v2.0.0"));

        assertEquals("staging", updated.name());
        assertEquals("web,mobile", updated.endTypes());
        assertEquals("v2.0.0", updated.versionSnapshot());
        assertEquals(1, envService.list(projectId).size(), "改名后仍唯一");
    }

    @Test
    void update_项目不存在_抛404() {
        initProject();
        long envId = envService.create(projectId, envInput("dev", 1));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.update(999999L, envId, envInput("x", 1)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void update_环境不存在_抛404() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.update(projectId, 999999L, envInput("x", 1)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void list_跨项目环境隔离() {
        long p1 = createProject("项目一", "PA");
        long p2 = createProject("项目二", "PB");
        envService.create(p1, envInput("dev", 1));
        envService.create(p2, envInput("dev", 1));

        List<ProjectEnvInfo> p1Envs = envService.list(p1);
        assertEquals(1, p1Envs.size(), "项目一环境列表不含项目二的环境（H1 隔离）");
        assertEquals(p1, p1Envs.get(0).projectId());
    }

    @Test
    void delete_删除成功() {
        initProject();
        long envId = envService.create(projectId, envInput("dev", 1));

        envService.delete(projectId, envId);

        assertEquals(0, envService.list(projectId).size(), "删除后环境列表为空");
    }

    @Test
    void delete_环境不存在_抛404() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.delete(projectId, 999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }


    @Test
    void create_四种类型可并存_项目级环境集合() {
        initProject();
        envService.create(projectId, envInput("dev", 1));
        envService.create(projectId, envInput("test", 2));
        envService.create(projectId, envInput("staging", 3));
        envService.create(projectId, envInput("prod", 4));

        List<ProjectEnvInfo> list = envService.list(projectId);
        assertEquals(4, list.size(), "项目 A 四套环境全创建成功");
    }

    @Test
    void create_同类型重复_409_类型唯一() {
        initProject();
        envService.create(projectId, envInput("dev", 1));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.create(projectId, envInput("dev-2", 1)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode(), "项目内环境类型唯一（uq_project_env_type）");
        assertTrue(ex.getMessage().contains("开发"), "人话提示应含环境类型名");
    }

    @Test
    void create_不同项目同类型_互不冲突() {
        long p1 = createProject("项目甲", "PA");
        long p2 = createProject("项目乙", "PB");
        envService.create(p1, envInput("dev", 1));
        envService.create(p1, envInput("test", 2));
        envService.create(p1, envInput("staging", 3));
        envService.create(p1, envInput("prod", 4));
        envService.create(p2, envInput("dev", 1));
        envService.create(p2, envInput("test", 2));
        envService.create(p2, envInput("staging", 3));

        assertEquals(4, envService.list(p1).size(), "项目 A 四套");
        assertEquals(3, envService.list(p2).size(), "项目 B 三套（不同项目环境集合不一致）");
    }

    @Test
    void update_改类型撞已有类型_409() {
        initProject();
        envService.create(projectId, envInput("dev", 1));
        long testId = envService.create(projectId, envInput("test", 2));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.update(projectId, testId, envInput("test-改", 1)), "编辑改类型撞已有开发环境应 409");
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode());
    }

    @Test
    void create_非法envType_5_参数校验() {
        initProject();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.create(projectId, envInput("bad", 5)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "v1.3 仅支持 1-4");
    }

    @Test
    void delete_跨项目环境_不生效_抛404() {
        long p1 = createProject("项目一", "PA");
        long p2 = createProject("项目二", "PB");
        long envId = envService.create(p2, envInput("dev", 1));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.delete(p1, envId), "从项目一删除项目二的环境应拒绝（H1 隔离）");
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertEquals(1, envService.list(p2).size(), "项目二环境未被误删");
    }
}
