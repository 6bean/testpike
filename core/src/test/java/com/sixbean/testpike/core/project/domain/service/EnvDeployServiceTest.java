package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.EnvDeployBatchRef;
import com.sixbean.testpike.core.project.api.dto.EnvDeployInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class EnvDeployServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectEnvService envService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

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

    private void initProjectAndEnv() {
        projectId = createProject("部署项目", "DEPLOY");
        envId = envService.create(projectId, new ProjectEnvInput("test", 2, "http://t.example.com",
                "{}", "web", "chromium", "v1.0.0"));
    }

    
    private long makeBatch(String versionSnapshot) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status,
                                           env_version_snapshot, type, schedule_type, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 1, 0, 5) RETURNING id
                """, Long.class, projectId, envId, "B-" + System.nanoTime(),
                "批次", 6, versionSnapshot);
    }

    @Test
    void recordDeploy_记录版本时间操作人_并同步版本快照() {
        initProjectAndEnv();
        EnvDeployInfo info = envService.recordDeploy(projectId, envId, "v2.0.1", 9L);

        assertEquals("v2.0.1", info.deployedVersion(), "部署版本落库");
        assertNotNull(info.deployedAt(), "部署时间落库（非空）");
        assertEquals(9L, info.deployedBy(), "部署操作人落库");
        ProjectEnvInfo env = envService.get(projectId, envId);
        assertEquals("v2.0.1", env.versionSnapshot(), "部署时同步 version_snapshot（批次快照口径一致）");
    }

    @Test
    void getDeployInfo_批次归因对照_版本一致matched() {
        initProjectAndEnv();
        long batchId = makeBatch("v2.0.1");

        EnvDeployInfo info = envService.recordDeploy(projectId, envId, "v2.0.1", 9L);
        EnvDeployInfo view = envService.getDeployInfo(projectId, envId);

        assertEquals(1, view.batches().size(), "归因对照含该环境批次");
        EnvDeployBatchRef ref = view.batches().get(0);
        assertEquals(batchId, ref.batchId());
        assertEquals("v2.0.1", ref.envVersionSnapshot());
        assertTrue(ref.matched(), "批次执行版本 == 当前部署版本 → 归因一致");
    }

    @Test
    void getDeployInfo_批次归因对照_版本落后未匹配() {
        initProjectAndEnv();
        makeBatch("v1.5.0");

        envService.recordDeploy(projectId, envId, "v2.0.0", 9L);
        EnvDeployInfo view = envService.getDeployInfo(projectId, envId);

        assertEquals(1, view.batches().size());
        EnvDeployBatchRef ref = view.batches().get(0);
        assertEquals("v1.5.0", ref.envVersionSnapshot());
        assertEquals(false, ref.matched(), "批次执行版本落后当前部署版本 → 归因不一致（失败归因提示环境版本已变化）");
    }

    @Test
    void getDeployInfo_未记录部署_版本空且批次未匹配() {
        initProjectAndEnv();
        makeBatch("v1.0.0");

        EnvDeployInfo view = envService.getDeployInfo(projectId, envId);

        assertNull(view.deployedVersion(), "未记录部署 → 部署版本为空");
        assertTrue(view.batches().isEmpty() || view.batches().stream().noneMatch(b -> b.matched()),
                "未记录部署 → 批次归因全部未匹配（无对照基线）");
    }

    @Test
    void recordDeploy_版本必填_400() {
        initProjectAndEnv();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.recordDeploy(projectId, envId, "  ", 9L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("版本"), "人话提示含「版本」");
    }

    @Test
    void recordDeploy_跨项目环境_404() {
        long otherProject = createProject("他项目", "OTH");
        long otherEnv = envService.create(otherProject, new ProjectEnvInput("test", 2,
                "http://o.example.com", "{}", "web", "chromium", "v1.0.0"));
        initProjectAndEnv();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.recordDeploy(projectId, otherEnv, "v9.0.0", 9L),
                "从本项目记录他项目环境部署 → 404（H1 隔离）");
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void recordDeploy_环境不存在_404() {
        initProjectAndEnv();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.recordDeploy(projectId, 999999L, "v1.0.0", 9L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void recordDeploy_版本超长_400() {
        initProjectAndEnv();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.recordDeploy(projectId, envId, "v" + "9".repeat(60), 9L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void getDeployInfo_跨项目_404() {
        long otherProject = createProject("他项目", "OTH2");
        long otherEnv = envService.create(otherProject, new ProjectEnvInput("test", 2,
                "http://o.example.com", "{}", "web", "chromium", "v1.0.0"));
        initProjectAndEnv();

        ServiceException ex = assertThrows(ServiceException.class,
                () -> envService.getDeployInfo(projectId, otherEnv));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void recordDeploy_多次部署_只保留最近一次() {
        initProjectAndEnv();
        envService.recordDeploy(projectId, envId, "v1.0.0", 9L);
        envService.recordDeploy(projectId, envId, "v1.0.1", 9L);

        EnvDeployInfo view = envService.getDeployInfo(projectId, envId);
        assertEquals("v1.0.1", view.deployedVersion(), "多次部署只保留最近一次");
    }

    @Test
    void getDeployInfo_批次归因对照_按时间倒序() {
        initProjectAndEnv();
        makeBatch("v1.0.0");
        makeBatch("v1.1.0");
        envService.recordDeploy(projectId, envId, "v1.1.0", 9L);

        EnvDeployInfo view = envService.getDeployInfo(projectId, envId);
        List<EnvDeployBatchRef> batches = view.batches();
        assertEquals(2, batches.size(), "两个批次均纳入归因对照");
        assertEquals("v1.1.0", batches.get(0).envVersionSnapshot(), "最近批次在前（id 倒序）");
        assertEquals(true, batches.get(0).matched());
        assertEquals(false, batches.get(1).matched(), "旧批次版本已过时");
    }
}
