package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ProjectServiceTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Creates a fixture project through the service path; sweeps a
     * crashed run's same-code leftover first, tracked for teardown. */
    private long createProject(String code, String name) {
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
        // Suite-owned owner accounts keep a fixed username prefix: targeted
        // pattern purge, factory seed users untouched.
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "T158\\_%");
    }

    
    private long createUser(String suffix) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id",
                Long.class, "T158_" + suffix, "T158用户" + suffix);
    }

    private ProjectCreateInput input(String code, String name) {
        return new ProjectCreateInput(name, code, "zh-CN", "P1", null);
    }

    @Test
    void create_创建成功且默认活跃_创建者自动成为项目负责人() {
        long id = createProject("PSHOP", "商品中台");

        ProjectInfo info = projectService.get(id);
        assertNotNull(info);
        assertEquals("商品中台", info.name());
        assertEquals("PSHOP", info.code());
        assertEquals(1, info.status(), "新建项目默认活跃");
        assertEquals(5L, info.createdBy(), "创建人记录");

        Integer role = jdbcTemplate.queryForObject(
                "SELECT role FROM tp_user_project WHERE project_id = ? AND user_id = ?",
                Integer.class, id, 5L);
        assertEquals(2, role, "创建者自动成为项目负责人（role=2）");
    }

    @Test
    void create_编码重复_抛409冲突() {
        createProject("DUP", "项目一");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> projectService.create(6L, input("DUP", "项目二")));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode(), "项目编码唯一冲突应 409");
    }

    @Test
    void create_编码为空_参数校验() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> createProject("", "无编码项目"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void list_管理员全量可见_成员仅所属项目() {
        // Shared dev db keeps factory seed rows alive, so visibility counts
        // are scoped through a per-run keyword marker instead of global totals.
        String marker = "psvm-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long p1 = createProject("PA", marker + "项目A");
        long p2 = createProject("PB", marker + "项目B");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", p1, 9L);

        PageResult<ProjectInfo> all = projectService.list(5L, true, marker, null, 1, 10);
        assertEquals(2, all.total());

        PageResult<ProjectInfo> mine = projectService.list(9L, false, marker, null, 1, 10);
        assertEquals(1, mine.total());
        assertEquals(p1, mine.list().get(0).id());
        assertEquals(1, mine.list().get(0).role(), "列表返回当前用户在项目的角色");
    }

    @Test
    void list_关键字筛选() {
        String marker = "pskw-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        createProject("POS", marker + "POS系统");
        createProject("ERP", marker + "ERP系统");

        PageResult<ProjectInfo> result = projectService.list(5L, true, marker + "pos", null, 1, 10);
        assertEquals(1, result.total(), "关键字命中 POS 系统");
        assertEquals("POS", result.list().get(0).code());
    }

    @Test
    void list_分页total正确() {
        String marker = "pspg-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i <= 5; i++) {
            createProject("PG" + i, marker + "项目" + i);
        }
        PageResult<ProjectInfo> page2 = projectService.list(5L, true, marker, null, 2, 2);
        assertEquals(5, page2.total(), "总数不受分页影响");
        assertEquals(2, page2.list().size(), "第 2 页 2 条");
        PageResult<ProjectInfo> page3 = projectService.list(5L, true, marker, null, 3, 2);
        assertEquals(1, page3.list().size(), "第 3 页剩 1 条");
    }

    @Test
    void get_不存在_抛404() {
        ServiceException ex = assertThrows(ServiceException.class, () -> projectService.get(999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void update_修改字段生效() {
        long id = createProject("UPD", "旧名");
        ProjectUpdateInput update = new ProjectUpdateInput("新名", "zh-TW", "P0", null,
                null, null, null, null, null, null, null);

        ProjectInfo updated = projectService.update(id, update);

        assertEquals("新名", updated.name());
        assertEquals("zh-TW", updated.defaultLang());
        assertEquals("P0", updated.defaultPriority());
        assertEquals("UPD", updated.code(), "项目编码创建后不可修改（用例 ID 前缀）");
    }

    @Test
    void update_项目不存在_抛404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> projectService.update(999999L, new ProjectUpdateInput("x", "zh-CN", "P1", null,
                        null, null, null, null, null, null, null)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }


    @Test
    void update_六列保存并回显_真实库落档() {
        long id = createProject("ATTRS", "属性项目");
        long testOwner = createUser("TO");
        long devOwner = createUser("DO");

        ProjectUpdateInput update = new ProjectUpdateInput("属性项目", "zh-CN", "P1", null,
                "TP2026", testOwner, devOwner, "/scripts/attrs", "/recordings/attrs", 2, null);
        ProjectInfo info = projectService.update(id, update);

        assertEquals("TP2026", info.jiraProjectId());
        assertEquals(testOwner, info.testOwnerId().longValue());
        assertEquals(devOwner, info.devOwnerId().longValue());
        assertEquals("/scripts/attrs", info.scriptStoragePath());
        assertEquals("/recordings/attrs", info.recordingStoragePath());
        assertEquals(2, info.storageMode());

        ProjectInfo reread = projectService.get(id);
        assertEquals("TP2026", reread.jiraProjectId());
        assertEquals(2, reread.storageMode(), "storage_mode 落库");
    }

    @Test
    void update_jira格式非法_400逐项人话() {
        long id = createProject("JIRA", "jira校验项目");
        for (String bad : new String[]{"tp2026", "TP 2026", "项目键", "1ABC", "ABCDEFGHIJK"}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> projectService.update(id, new ProjectUpdateInput("jira校验项目", "zh-CN", "P1", null,
                            bad, null, null, null, null, null, null)),
                    "非法 jiraProjectId 应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), bad);
            assertTrue(ex.getMessage().contains("JIRA"), "人话文案应含 JIRA: " + ex.getMessage());
        }
    }

    @Test
    void update_owner不存在_400() {
        long id = createProject("OWNER", "负责人校验项目");
        long realUser = createUser("REAL");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> projectService.update(id, new ProjectUpdateInput("负责人校验项目", "zh-CN", "P1", null,
                        null, 999999L, null, null, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("测试负责人"), "人话文案定位到具体项: " + ex.getMessage());

        ProjectInfo info = projectService.update(id, new ProjectUpdateInput("负责人校验项目", "zh-CN", "P1", null,
                null, realUser, null, null, null, null, null));
        assertEquals(realUser, info.testOwnerId().longValue());
    }

    @Test
    void update_存储路径超300字_400() {
        long id = createProject("PATH", "路径校验项目");
        String tooLong = "/s".repeat(200);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> projectService.update(id, new ProjectUpdateInput("路径校验项目", "zh-CN", "P1", null,
                        null, null, null, tooLong, null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("脚本"), "人话文案定位到具体项: " + ex.getMessage());

        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> projectService.update(id, new ProjectUpdateInput("路径校验项目", "zh-CN", "P1", null,
                        null, null, null, null, tooLong, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex2.getCode());
        assertTrue(ex2.getMessage().contains("录制"), "人话文案定位到具体项: " + ex2.getMessage());
    }

    @Test
    void update_存储模式非法_400() {
        long id = createProject("MODE", "模式校验项目");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> projectService.update(id, new ProjectUpdateInput("模式校验项目", "zh-CN", "P1", null,
                        null, null, null, null, null, 9, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("存储模式"), "人话文案: " + ex.getMessage());
    }

    @Test
    void update_六列传空_回退默认语义() {
        long id = createProject("RESET", "回退项目");
        projectService.update(id, new ProjectUpdateInput("回退项目", "zh-CN", "P1", null,
                "RSV1", null, null, "/a", "/b", 3, null));
        assertEquals(3, projectService.get(id).storageMode());

        projectService.update(id, new ProjectUpdateInput("回退项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null));
        ProjectInfo reset = projectService.get(id);
        assertNull(reset.jiraProjectId(), "未传=清除，缺陷 key 回退既有语义的前提（IT-PRJ-02）");
        assertNull(reset.scriptStoragePath());
        assertNull(reset.recordingStoragePath());
        assertEquals(1, reset.storageMode(), "storageMode 传空=系统默认");
    }


    @Test
    void create_kbEnabled默认开_V72存量零变化() {
        long id = createProject("KBON", "开关项目");
        assertEquals(1, projectService.get(id).kbEnabled(), "V72 默认 1=开=审批通过自动入库（存量行为零变化）");
    }

    @Test
    void update_kbEnabled往返_真实库落档() {
        long id = createProject("KBRT", "往返项目");
        projectService.update(id, new ProjectUpdateInput("往返项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, 0));
        assertEquals(0, projectService.get(id).kbEnabled(), "关态落库（reload 语义不信任返回值）");

        projectService.update(id, new ProjectUpdateInput("往返项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, 1));
        assertEquals(1, projectService.get(id).kbEnabled(), "改回开=往返持久化（TC-KBS-01 第三段前提）");
    }

    @Test
    void update_kbEnabled非法值_400人话() {
        long id = createProject("KBBAD", "非法开关项目");
        for (int bad : new int[]{2, -1}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> projectService.update(id, new ProjectUpdateInput("非法开关项目", "zh-CN", "P1", null,
                            null, null, null, null, null, null, bad)),
                    "非法 kbEnabled 应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("知识库开关"), "人话文案: " + ex.getMessage());
        }
    }

    @Test
    void update_kbEnabled传空_回退开() {
        long id = createProject("KBNULL", "空值开关项目");
        projectService.update(id, new ProjectUpdateInput("空值开关项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, 0));
        assertEquals(0, projectService.get(id).kbEnabled());
        projectService.update(id, new ProjectUpdateInput("空值开关项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null));
        assertEquals(1, projectService.get(id).kbEnabled(), "kbEnabled 传空=回退默认开");
    }


    @Test
    void create_notifyFeishu_默认仅站内() {
        long id = createProject("FNORM", "常规项目");
        assertEquals(0, projectService.get(id).notifyFeishu(), "V86 默认 0=仅站内=存量行为零变化");
    }

    @Test
    void update_notifyFeishu_往返_真实库落档() {
        long id = createProject("FIRT", "飞书往返项目");
        projectService.update(id, new ProjectUpdateInput("飞书往返项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null, 1));
        assertEquals(1, projectService.get(id).notifyFeishu(), "开态落库（reload 语义不信任返回值）");
        projectService.update(id, new ProjectUpdateInput("飞书往返项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null, 0));
        assertEquals(0, projectService.get(id).notifyFeishu(), "改回关=往返持久化");
    }

    @Test
    void update_notifyFeishu_传空_回退关() {
        long id = createProject("FNUL", "飞书空值项目");
        projectService.update(id, new ProjectUpdateInput("飞书空值项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null, 1));
        projectService.update(id, new ProjectUpdateInput("飞书空值项目", "zh-CN", "P1", null,
                null, null, null, null, null, null, null, null));
        assertEquals(0, projectService.get(id).notifyFeishu(), "全量替换语义：传空=回退 DB 默认 0=仅站内");
    }

    @Test
    void update_notifyFeishu_非法值_400人话() {
        long id = createProject("FBAD", "飞书非法项目");
        for (int bad : new int[]{2, -1}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> projectService.update(id, new ProjectUpdateInput("飞书非法项目", "zh-CN", "P1", null,
                            null, null, null, null, null, null, null, bad)),
                    "非法 notifyFeishu 应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("飞书"), "人话文案: " + ex.getMessage());
        }
    }

    @Test
    void archive_归档后列表不可见_数据保留() {
        long id = createProject("ARC", "待归档项目");
        ProjectInfo archived = projectService.archive(id);
        assertEquals(2, archived.status(), "归档状态=2");

        PageResult<ProjectInfo> list = projectService.list(5L, true, null, 1, 1, 10);
        assertTrue(list.list().stream().noneMatch(p -> p.id() == id), "归档项目不出现于活跃列表");

        assertNotNull(projectService.get(id));
    }

    @Test
    void archive_项目不存在_抛404() {
        ServiceException ex = assertThrows(ServiceException.class, () -> projectService.archive(999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void getMemberRole_负责人成员非成员() {
        long id = createProject("ROLE", "角色项目");
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)", id, 9L);

        assertEquals(2, projectService.getMemberRole(id, 5L), "创建者为负责人");
        assertEquals(1, projectService.getMemberRole(id, 9L), "普通成员");
        assertEquals(0, projectService.getMemberRole(id, 999L), "非成员");
    }

    
    @Test
    void classifyVision_默认0且读路径带出开关值() {
        long id = createProject("VIS", "视觉归因项目");
        assertEquals(0, projectService.get(id).classifyVision(), "新建项目默认关（列 NOT NULL default 0）");

        jdbcTemplate.update("UPDATE tp_project SET classify_vision = 1 WHERE id = ?", id);
        assertEquals(1, projectService.get(id).classifyVision(), "开关置 1 读路径带出");
    }

    @Test
    void list_状态筛选只返回活跃() {
        String marker = "psst-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long id = createProject("ST", marker + "状态项目");
        projectService.archive(id);

        PageResult<ProjectInfo> active = projectService.list(5L, true, marker, 1, 1, 10);
        assertTrue(active.list().stream().noneMatch(p -> p.id() == id), "状态筛选=1 不含归档");

        PageResult<ProjectInfo> archived = projectService.list(5L, true, marker, 2, 1, 10);
        assertEquals(1, archived.total(), "状态筛选=2 仅归档项目");
    }

    // ── page 13: server-side sorting over the project window ──
    // Fixture: four projects p1..p4 created in sequence with strictly ascending
    // codes (T292B5A..D) and names marker + -a/-m/-m/-z, so two rows tie on the
    // name column and prove the constant id DESC tie-break. created_at rises per
    // creation transaction, making the historical created_at DESC,id DESC default
    // strictly assertable as [p4,p3,p2,p1] even when timestamps collide.

    /** Seed p1..p4 under one unique name marker; returns their ids in creation order. */
    private long[] seedSortFixture(String marker) {
        long p1 = createProject("T292B5A", marker + "-a");
        long p2 = createProject("T292B5B", marker + "-m");
        long p3 = createProject("T292B5C", marker + "-m");
        long p4 = createProject("T292B5D", marker + "-z");
        return new long[]{p1, p2, p3, p4};
    }

    private List<Long> projectIdsSorted(String marker, String sortBy, String sortDir,
                                        int page, int size) {
        return projectService.list(5L, true, marker, null, page, size, sortBy, sortDir)
                .list().stream().map(ProjectInfo::id).toList();
    }

    private List<Long> idsOf(long[] seeded, int... indexes) {
        return java.util.Arrays.stream(indexes).mapToObj(i -> seeded[i]).toList();
    }

    /** No sort key keeps the historical created_at DESC,id DESC default; the
     * legacy six-argument call delegates to the same default (zero drift). */
    @Test
    void test_sort_absent_key_keeps_created_at_desc_and_legacy_overload_delegates() {
        String marker = "t292b5p-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(marker);
        List<Long> newestFirst = idsOf(seeded, 3, 2, 1, 0);
        assertEquals(newestFirst, projectIdsSorted(marker, null, null, 1, 20));
        assertEquals(newestFirst, projectIdsSorted(marker, "", "", 1, 20));
        List<Long> viaLegacy = projectService.list(5L, true, marker, null, 1, 20)
                .list().stream().map(ProjectInfo::id).toList();
        assertEquals(newestFirst, viaLegacy, "legacy 6-arg overload must not drift");
    }

    /** name asc follows the -a/-m/-z suffixes; the two tied -m rows stay
     * deterministic through the id DESC tie-break (newer first). */
    @Test
    void test_sort_name_column_tied_rows_pinned_by_id_desc() {
        String marker = "t292b5p-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(marker);
        assertEquals(idsOf(seeded, 0, 2, 1, 3), projectIdsSorted(marker, "name", "asc", 1, 20));
        assertEquals(idsOf(seeded, 3, 2, 1, 0), projectIdsSorted(marker, "name", "desc", 1, 20));
    }

    /** code asc/desc mirror each other (codes strictly ascend with creation). */
    @Test
    void test_sort_code_column_both_directions() {
        String marker = "t292b5p-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(marker);
        assertEquals(idsOf(seeded, 0, 1, 2, 3), projectIdsSorted(marker, "code", "asc", 1, 20));
        assertEquals(idsOf(seeded, 3, 2, 1, 0), projectIdsSorted(marker, "code", "desc", 1, 20));
    }

    /** Sorting keeps paging consistent: page 2 continues the ordered window
     * and total stays truthful. */
    @Test
    void test_sort_applies_across_pages() {
        String marker = "t292b5p-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long[] seeded = seedSortFixture(marker);
        PageResult<ProjectInfo> first = projectService.list(
                5L, true, marker, null, 1, 2, "code", "asc");
        PageResult<ProjectInfo> second = projectService.list(
                5L, true, marker, null, 2, 2, "code", "asc");
        assertEquals(idsOf(seeded, 0, 1), first.list().stream().map(ProjectInfo::id).toList());
        assertEquals(idsOf(seeded, 2, 3), second.list().stream().map(ProjectInfo::id).toList());
        assertEquals(4L, first.total());
    }

    /** An injection-shaped or off-list key is refused fail-closed with a human
     * 400 naming the whitelist; rows stay intact afterwards. */
    @Test
    void test_sort_off_list_key_refused_with_whitelist_hint() {
        String marker = "t292b5p-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        seedSortFixture(marker);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> projectIdsSorted(marker, "created_at; DROP TABLE tp_project", "asc", 1, 20));
        assertEquals(BizErrorCode.VALIDATION_ERROR, rejected.getCode());
        assertTrue(rejected.getMessage().contains("created_at"),
                "error should name the allowed columns, actual=" + rejected.getMessage());
        assertEquals(4, projectIdsSorted(marker, null, null, 1, 20).size(), "rows must be unaffected");
    }
}
