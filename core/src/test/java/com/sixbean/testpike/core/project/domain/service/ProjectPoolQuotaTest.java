package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ProjectPoolQuotaTest {

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

    private static ProjectUpdateInput input(Integer poolQuotaPct) {
        return new ProjectUpdateInput("配额项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null, null, poolQuotaPct);
    }

    @Test
    void update_配额保存并回显_真实库落档() {
        long id = createProject("PQ1");

        ProjectInfo updated = projectService.update(id, input(30));

        assertEquals(30, updated.poolQuotaPct(), "更新响应回显配额");
        assertEquals(30, projectService.get(id).poolQuotaPct(), "详情读取同源");
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT pool_quota_pct FROM tp_project WHERE id = ?", Integer.class, id);
        assertEquals(30, stored, "pool_quota_pct 真实落库");
    }

    @Test
    void update_配额越界_400人话() {
        long id = createProject("PQ2");

        for (int illegal : new int[]{0, 101, -5}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> projectService.update(id, input(illegal)));
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("配额"), "人话定位配额字段: " + ex.getMessage());
        }
    }

    @Test
    void update_留空清除_回不限() {
        long id = createProject("PQ3");
        projectService.update(id, input(50));

        ProjectInfo cleared = projectService.update(id, input(null));

        assertNull(cleared.poolQuotaPct(), "null=不限（全量替换清除）");
        assertNull(projectService.get(id).poolQuotaPct());
    }

    @Test
    void update_旧构造兼容_配额保持全量替换语义() {
        long id = createProject("PQ4");
        projectService.update(id, input(80));

        ProjectInfo viaLegacy = projectService.update(id, new ProjectUpdateInput("旧构造项目",
                "zh-CN", "P1", BigDecimal.ONE, null, null, null, null, null, null, null, null));
        assertNull(viaLegacy.poolQuotaPct(), "旧构造未带配额=按全量替换清除（UI 层必带完整字段）");
    }
}
