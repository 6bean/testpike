package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput;
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
class ProjectPageInspectTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
    }

    private long createProject(String code) {
        SharedDbFixtureSweep.byProject(jdbcTemplate,
                SharedDbFixtureSweep.projectIdByCode(jdbcTemplate, code));
        long id = projectService.create(1L,
                new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private static ProjectUpdateInput input(Integer pageInspectEnabled) {
        return new ProjectUpdateInput("探查开关项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null, null, null, pageInspectEnabled);
    }

    
    @Test
    void 新建项目_探查开关默认0() {
        long id = createProject("PI1");
        assertEquals(0, projectService.get(id).pageInspectEnabled(), "默认关（TC-G4-01 判型①零外呼前提）");
    }

    
    @Test
    void update_开关保存回显_真实库落档() {
        long id = createProject("PI2");

        ProjectInfo updated = projectService.update(id, input(1));

        assertEquals(1, updated.pageInspectEnabled(), "更新响应回显开关");
        assertEquals(1, projectService.get(id).pageInspectEnabled(), "详情读取同源");
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT ai_page_inspect_enabled FROM tp_project WHERE id = ?", Integer.class, id);
        assertEquals(1, stored, "DB 落档同源");
    }

    
    @Test
    void update_非法开关值_40001人话() {
        long id = createProject("PI3");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> projectService.update(id, input(2)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("页面探查"), "人话点名字段：" + ex.getMessage());
    }

    
    @Test
    void update_空值回退关_全量替换语义() {
        long id = createProject("PI4");
        projectService.update(id, input(1));
        assertEquals(1, projectService.get(id).pageInspectEnabled());

        projectService.update(id, input(null));

        assertEquals(0, projectService.get(id).pageInspectEnabled(), "留空回退 0=关");
    }
}
