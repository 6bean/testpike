package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingInput;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolBindingItem;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolItem;
import com.sixbean.testpike.core.tool.api.dto.ProjectToolView;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ProjectToolServiceTest {

    @Autowired
    private ProjectToolService projectToolService;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture tools swept by id tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureTools = new java.util.ArrayList<>();
    /** Fixture projects swept by FK tree; never a table wipe (R37). */
    private final java.util.List<Long> fixtureProjects = new java.util.ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        SharedDbFixtureSweep.toolFixtureDown(jdbcTemplate, fixtureTools,
                fixtureProjects, java.util.List.of());
    }

    // ---- helpers ----

    private long createProject(String code) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES (?, ?, 'zh-CN', 1) RETURNING id
                """, Long.class, "项目" + code, code);
        fixtureProjects.add(id);
        return id;
    }

 
    private long createPublishedTool(Long projectId, String name) {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(projectId, name, 1,
                "取数", "测试数据规范", "正文内容", null, null, null, null, null, null, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        long approvedId = toolService.approve(2L, submitted.id(), submitted.version()).id();
        fixtureTools.add(approvedId);
        return approvedId;
    }

    private long createDraftTool(Long projectId, String name) {
        long draftId = toolService.create(1L, new ToolCreateInput(projectId, name, 1,
                "取数", null, "正文内容", null, null, null, null, null, null, null)).id();
        fixtureTools.add(draftId);
        return draftId;
    }

    private static ProjectToolBindingInput input(List<ProjectToolBindingItem> tools,
                                                 Long expectedVersion) {
        return new ProjectToolBindingInput(tools, expectedVersion);
    }

    private ProjectToolItem itemOf(ProjectToolView view, long toolId) {
        return view.list().stream().filter(i -> i.toolId() == toolId).findFirst().orElseThrow();
    }

    private int bindingCount(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_tool WHERE project_id = ?",
                Integer.class, projectId);
        return n == null ? 0 : n;
    }

    private int bindAuditCount(long projectId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'project_tool' "
                        + "AND action = 'bind' AND object_id = ?",
                Integer.class, String.valueOf(projectId));
        return n == null ? 0 : n;
    }


    @Test
    void 全量启用_系统级与项目级两行落库_回显enabled与类型人话() {
        long pid = createProject("PTL1");
        long sysTool = createPublishedTool(null, "系统规范工具");
        long prjTool = createPublishedTool(pid, "项目取数工具");

        ProjectToolView view = projectToolService.saveBindings(7L, pid, input(
                List.of(new ProjectToolBindingItem(sysTool, 1),
                        new ProjectToolBindingItem(prjTool, 0)), null));

        assertEquals(2, view.list().size());
        ProjectToolItem sys = itemOf(view, sysTool);
        ProjectToolItem prj = itemOf(view, prjTool);
        assertEquals(1, sys.enabled());
        assertEquals(0, prj.enabled());
        assertEquals("系统规范工具", sys.name());
        assertEquals("文档类", sys.typeLabel());
        assertNotNull(sys.boundAt());
        assertTrue(view.version() > 0, "revision 应为非零 epoch 微秒");
        assertEquals(2, bindingCount(pid));
        assertEquals(1, bindAuditCount(pid));
    }


    @Test
    void 全量替换_集合外自动解绑_空集合清空回version0() {
        long pid = createProject("PTL2");
        long a = createPublishedTool(null, "甲");
        long b = createPublishedTool(null, "乙");

        projectToolService.saveBindings(1L, pid,
                input(List.of(new ProjectToolBindingItem(a, 1),
                        new ProjectToolBindingItem(b, 1)), null));
        assertEquals(2, bindingCount(pid));

        ProjectToolView replaced = projectToolService.saveBindings(1L, pid,
                input(List.of(new ProjectToolBindingItem(b, 1)), null));
        assertEquals(1, replaced.list().size());
        assertEquals(1, bindingCount(pid));
        assertNotNull(itemOf(replaced, b));

        ProjectToolView cleared = projectToolService.saveBindings(1L, pid, input(List.of(), null));
        assertEquals(0, cleared.list().size());
        assertEquals(0, cleared.version(), "空集 version=0（ProjectSkillView 契约口径）");
        assertEquals(0, bindingCount(pid));
    }


    @Test
    void 草稿工具拒启用_点名当前状态() {
        long pid = createProject("PTL3");
        long draft = createDraftTool(pid, "未发布工具");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pid,
                        input(List.of(new ProjectToolBindingItem(draft, 1)), null)));
        assertTrue(ex.getMessage().contains("未发布"), ex.getMessage());
        assertTrue(ex.getMessage().contains("未发布工具"), "应点名工具名：" + ex.getMessage());
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertEquals(0, bindingCount(pid), "校验失败零写入");
    }


    @Test
    void 跨项目项目级工具拒绑_系统级全局可绑() {
        long pidA = createProject("PTLA");
        long pidB = createProject("PTLB");
        long toolOfB = createPublishedTool(pidB, "B项目专属");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pidA,
                        input(List.of(new ProjectToolBindingItem(toolOfB, 1)), null)));
        assertTrue(ex.getMessage().contains("其他项目"), ex.getMessage());
        assertEquals(0, bindingCount(pidA));

        long sysTool = createPublishedTool(null, "全局工具");
        ProjectToolView ok = projectToolService.saveBindings(1L, pidA,
                input(List.of(new ProjectToolBindingItem(sysTool, 1)), null));
        assertEquals(1, ok.list().size());
    }


    @Test
    void 校验族逐项人话_零写入() {
        long pid = createProject("PTL4");
        long tool = createPublishedTool(null, "工具甲");

        ServiceException nullList = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pid, input(null, null)));
        assertTrue(nullList.getMessage().contains("不能为空"), nullList.getMessage());

        ServiceException badEnabled = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pid,
                        input(List.of(new ProjectToolBindingItem(tool, 2)), null)));
        assertTrue(badEnabled.getMessage().contains("0"), badEnabled.getMessage());

        ServiceException dup = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pid,
                        input(List.of(new ProjectToolBindingItem(tool, 1),
                                new ProjectToolBindingItem(tool, 0)), null)));
        assertTrue(dup.getMessage().contains("重复"), dup.getMessage());

        ServiceException missing = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pid,
                        input(List.of(new ProjectToolBindingItem(999999L, 1)), null)));
        assertEquals(BizErrorCode.NOT_FOUND, missing.getCode());

        ServiceException nullId = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pid,
                        input(List.of(new ProjectToolBindingItem(null, 1)), null)));
        assertNotNull(nullId.getMessage());

        assertEquals(0, bindingCount(pid), "全部校验失败零写入");
        assertEquals(0, bindAuditCount(pid));
    }


    @Test
    void 乐观锁陈旧40901含当前版本_null强覆盖() {
        long pid = createProject("PTL5");
        long tool = createPublishedTool(null, "锁工具");

        ProjectToolView first = projectToolService.saveBindings(1L, pid,
                input(List.of(new ProjectToolBindingItem(tool, 1)), null));
        long revision = first.version();

        ServiceException stale = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, pid,
                        input(List.of(new ProjectToolBindingItem(tool, 0)), revision - 1)));
        assertEquals(BizErrorCode.VERSION_CONFLICT, stale.getCode());
        assertTrue(stale.getMessage().contains(String.valueOf(revision)),
                "报错应含当前版本：" + stale.getMessage());
        assertEquals(1, itemOf(projectToolService.getBindings(pid), tool).enabled(),
                "冲突保存零生效");

        ProjectToolView forced = projectToolService.saveBindings(1L, pid,
                input(List.of(new ProjectToolBindingItem(tool, 0)), null));
        assertEquals(0, itemOf(forced, tool).enabled());
    }

    // ---- (7) Same-set replay = idempotent no-op (ProjectSkillServiceTest same
    // posture): rows untouched, bound-at/revision frozen, no audit row appended ----

    @Test
    void 同集合重放保存_零写入_boundAt与revision不动_审计不追加() {
        long pid = createProject("PTL7");
        long tool = createPublishedTool(null, "幂等工具");
        ProjectToolView v1 = projectToolService.saveBindings(1L, pid,
                input(List.of(new ProjectToolBindingItem(tool, 1)), null));
        String boundAt1 = itemOf(v1, tool).boundAt();

        ProjectToolView v2 = projectToolService.saveBindings(1L, pid,
                input(List.of(new ProjectToolBindingItem(tool, 1)), v1.version()));

        assertEquals(v1.version(), v2.version(), "same-set replay keeps revision frozen");
        assertEquals(boundAt1, itemOf(v2, tool).boundAt(), "same-set replay keeps enabled-since frozen");
        assertEquals(1, bindAuditCount(pid), "same-set replay appends no audit row");

        long pid2 = createProject("PTL8");
        ProjectToolView empty = projectToolService.saveBindings(1L, pid2, input(List.of(), null));
        assertEquals(0, empty.version(), "empty-set replay: version stays 0");
        assertEquals(0, bindAuditCount(pid2), "empty-set replay writes no audit row");
    }


    @Test
    void 项目不存在拒配置_getBindings联查回显() {
        ServiceException ex = assertThrows(ServiceException.class, () ->
                projectToolService.saveBindings(1L, 999998L, input(List.of(), null)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        ServiceException read = assertThrows(ServiceException.class, () ->
                projectToolService.getBindings(999998L));
        assertEquals(BizErrorCode.NOT_FOUND, read.getCode());

        long pid = createProject("PTL6");
        long tool = createPublishedTool(pid, "回显工具");
        projectToolService.saveBindings(1L, pid,
                input(List.of(new ProjectToolBindingItem(tool, 1)), null));
        ProjectToolView view = projectToolService.getBindings(pid);
        assertEquals(1, view.list().size());
        assertEquals("回显工具", itemOf(view, tool).name());
        assertEquals(1, itemOf(view, tool).type());
        assertEquals("文档类", itemOf(view, tool).typeLabel());
    }
}
