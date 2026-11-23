package com.sixbean.testpike.core.member.domain.service;

import com.sixbean.testpike.core.member.api.dto.UserProjectInfo;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.member.domain.service.UserProjectQueryService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>R37 fixture contract: every tp_project/tp_user row is created by this
 * class with a run token and torn down by tracked id (the old blanket
 * {@code TRUNCATE tp_user_project, tp_project CASCADE} wiped the 40+ shipped
 * projects of the shared dev database). Whole-table count assertions are
 * re-anchored on the live active-project count / a freshly seeded user, so
 * they keep testing the same semantics without the wipe.
 *
 */
@SpringBootTest(classes = CoreApplication.class)
class UserProjectQueryServiceTest {

    @Autowired
    private UserProjectQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Run-owned fixture ledger: swept by tracked id in @AfterEach. */
    private final List<Long> projectIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private String runToken;

    @BeforeEach
    void prepareRunToken() {
        runToken = SharedDbFixtureSweep.newToken();
    }

    @AfterEach
    void sweepFixtureRows() {
        // byProjectList walks the FK graph child-first: membership rows of the
        // fixture projects (tp_user_project, FK into tp_project) go with them.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, projectIds);
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_user", userIds);
    }

    private String code(String base) {
        return base + "_" + runToken;
    }

    private long insertProject(String code, String name, int status) {
        long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (code, name, status, default_lang) VALUES (?, ?, ?, 'zh-CN') RETURNING id",
                Long.class, code, name, status);
        projectIds.add(id);
        return id;
    }

    /** Fresh seeded user: nobody else can hold membership rows for this id. */
    private long seedMemberUser() {
        long id = SharedDbFixtureSweep.seedUser(jdbcTemplate,
                "upq_" + runToken + "_" + userIds.size(), "UPQ member " + userIds.size());
        userIds.add(id);
        return id;
    }

    private void insertMember(long projectId, long userId, int role) {
        jdbcTemplate.update(
                "INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, ?)",
                projectId, userId, role);
    }

    /** Live anchor for the system-manager lens: active = status 1, shipped rows included. */
    private int activeProjectCount() {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project WHERE status = 1", Integer.class);
        return c == null ? 0 : c;
    }

    @Test
    void findByUserId_返回活跃项目列表与角色() {
        long uid = seedMemberUser();
        String c1 = code("PSHOP");
        String c2 = code("POS");
        long p1 = insertProject(c1, "商品中台", 1);
        long p2 = insertProject(c2, "POS 系统", 1);
        insertMember(p1, uid, 2); // project owner
        insertMember(p2, uid, 1); // member

        List<UserProjectInfo> list = queryService.findByUserId(uid);

        assertEquals(2, list.size(), "新建账号应只见到本 run 的 2 个活跃项目");
        UserProjectInfo r1 = list.stream().filter(r -> r.projectId() == p1).findFirst().orElseThrow();
        assertEquals(c1, r1.projectCode());
        assertEquals("商品中台", r1.projectName());
        assertEquals(2, r1.role());
        assertEquals("项目负责人", r1.roleLabel());
        UserProjectInfo r2 = list.stream().filter(r -> r.projectId() == p2).findFirst().orElseThrow();
        assertEquals(1, r2.role());
        assertEquals("成员", r2.roleLabel());
    }

    @Test
    void findByUserId_已归档项目不返回() {
        long uid = seedMemberUser();
        long p1 = insertProject(code("ACTIVE"), "活跃项目", 1);
        long p2 = insertProject(code("ARCHIVED"), "已归档项目", 2);
        insertMember(p1, uid, 1);
        insertMember(p2, uid, 2);

        List<UserProjectInfo> list = queryService.findByUserId(uid);

        assertEquals(1, list.size(), "已归档项目不得出现在用户项目列表");
        assertEquals(p1, list.get(0).projectId());
    }

    @Test
    void findByUserId_无成员返回空列表() {
        insertProject(code("EMPTY"), "无成员项目", 1);
        long uid = seedMemberUser();
        List<UserProjectInfo> list = queryService.findByUserId(uid);
        assertTrue(list.isEmpty(), "无成员关系的用户应返回空列表");
    }


    @Test
    void findVisible_系统管理角色谱_全部活跃项目可见且归档仍排除() {
        long uid = seedMemberUser();
        long p1 = insertProject(code("OWNED"), "我的项目", 1);
        long p2 = insertProject(code("OTHER"), "别人的项目", 1);
        long archived = insertProject(code("ARCH"), "归档项目", 2);
        insertMember(p1, uid, 2);

        int activeTotal = activeProjectCount();
        List<UserProjectInfo> list = queryService.findVisibleByUserId(uid, true);

        // Same semantics as the pre-R37 "2 rows" pin, re-anchored: the system
        // manager lens must show EVERY active project of the shared database.
        assertEquals(activeTotal, list.size(), "系统管理角色谱=全部活跃项目可见（归档仍排除）");
        UserProjectInfo own = list.stream().filter(r -> r.projectId() == p1).findFirst().orElseThrow();
        assertEquals(2, own.role(), "本人项目保留真实成员角色谱");
        assertEquals("项目负责人", own.roleLabel());
        UserProjectInfo other = list.stream().filter(r -> r.projectId() == p2).findFirst().orElseThrow();
        assertEquals(0, other.role(), "非本人项目 role=0（诚实标注非成员，不谎报成员身份）");
        assertEquals("系统角色可见", other.roleLabel());
        assertTrue(list.stream().noneMatch(r -> r.projectId() == archived), "归档项目不得出现在可见谱");
    }

    @Test
    void findVisible_成员谱_仅本人项目可见不变() {
        long uid = seedMemberUser();
        long p1 = insertProject(code("MINE"), "我的项目", 1);
        insertProject(code("FOREIGN"), "他人项目", 1);
        insertMember(p1, uid, 1);

        List<UserProjectInfo> list = queryService.findVisibleByUserId(uid, false);

        assertEquals(1, list.size(), "H1 隔离零放宽：普通成员仍只见本人归属项目");
        assertEquals(p1, list.get(0).projectId());
        assertEquals("成员", list.get(0).roleLabel());
    }

    @Test
    void findVisible_系统管理角色谱_无任何成员关系时全部为非成员谱() {
        long p1 = insertProject(code("X1"), "项目X1", 1);
        long p2 = insertProject(code("X2"), "项目X2", 1);
        long uid = seedMemberUser();

        int activeTotal = activeProjectCount();
        List<UserProjectInfo> list = queryService.findVisibleByUserId(uid, true);

        assertEquals(activeTotal, list.size(), "系统管理角色无成员关系也应看到全部活跃项目（头部下拉有货硬判据）");
        // Without any membership the lens is uniformly the non-member spectrum.
        assertTrue(list.stream().allMatch(r -> r.role() == 0 && "系统角色可见".equals(r.roleLabel())),
                "全部条目应为非成员谱");
        assertTrue(list.stream().anyMatch(r -> r.projectId() == p1)
                && list.stream().anyMatch(r -> r.projectId() == p2), "本 run 项目必须在可见集内");
    }

    @Test
    void findByUserId_多用户隔离互不串扰() {
        long u1 = seedMemberUser();
        long u2 = seedMemberUser();
        long p1 = insertProject(code("ISO1"), "项目一", 1);
        long p2 = insertProject(code("ISO2"), "项目二", 1);
        insertMember(p1, u1, 2);
        insertMember(p2, u2, 1);

        List<UserProjectInfo> l1 = queryService.findByUserId(u1);
        List<UserProjectInfo> l2 = queryService.findByUserId(u2);

        assertEquals(1, l1.size());
        assertEquals(p1, l1.get(0).projectId());
        assertEquals(1, l2.size());
        assertEquals(p2, l2.get(0).projectId(), "不同用户的项目必须隔离");
    }
}
