package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectAccessService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


@SpringBootTest(classes = CoreApplication.class)
class ProjectAccessServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectAccessService accessService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Creates a fixture project through the service path; sweeps a
     * crashed run's same-code leftover first, tracked for teardown. */
    private long createProject(String code) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(5L, new ProjectCreateInput("项目", code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    @Test
    void getMemberRole_非成员返回0() {
        long id = createProject("PA");
        assertEquals(0, accessService.getMemberRole(id, 999L));
    }

    @Test
    void requireProjectMember_非成员_403() {
        long id = createProject("PA");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> accessService.requireProjectMember(id, 999L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "非项目成员访问项目资源 403（H1 隔离）");
    }

    @Test
    void requireProjectMember_成员通过() {
        long id = createProject("PA");
        accessService.requireProjectMember(id, 5L);
    }

    @Test
    void requireProjectOwner_普通成员_403() {
        long id = createProject("PA");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", id, 9L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> accessService.requireProjectOwner(id, 9L));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "普通成员执行项目级管理操作 403");
    }

    @Test
    void requireProjectOwner_负责人通过() {
        long id = createProject("PA");
        accessService.requireProjectOwner(id, 5L);
    }

    @Test
    void requireProjectMember_项目不存在_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> accessService.requireProjectMember(999999L, 5L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }
}
