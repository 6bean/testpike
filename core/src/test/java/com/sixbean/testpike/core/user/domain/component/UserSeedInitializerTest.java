package com.sixbean.testpike.core.user.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.api.dto.UserInfo;
import com.sixbean.testpike.core.user.api.dto.UserQuery;
import com.sixbean.testpike.core.user.domain.component.UserSeedInitializer;
import com.sixbean.testpike.core.user.domain.service.UserService;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


@SpringBootTest(classes = CoreApplication.class)
class UserSeedInitializerTest {

    @Autowired
    private UserSeedInitializer initializer;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Suite username prefix; the LIKE sweep below is the only teardown channel. */
    private static final String PREFIX = "fxsd_";

    @BeforeEach
    void dropStaleFixtureUsers() {
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, PREFIX + "%");
    }

    @AfterEach
    void sweepFixtureUsers() {
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, PREFIX + "%");
    }

    @Test
    void 缺席_有密码_创建系统管理员() {
        String name = PREFIX + "seed_admin";
        initializer.initialize(name, "SeedPass!2026a");

        assertEquals(1, userService.list(new UserQuery(name, null, null, 1, 20)).total(),
                "缺席即建，恰 1 个用户");
        UserInfo admin = userService.authenticate(name, "SeedPass!2026a");
        assertEquals(4, admin.role(), "种子为系统管理员（role=4）");
        assertEquals(1, admin.status(), "默认启用");
        assertNotNull(admin.id());
        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM tp_user WHERE id = ?", String.class, admin.id());
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希");
        assertTrue(!hash.contains("SeedPass!2026a"), "禁明文");
    }

    @Test
    void 缺席_无密码_跳过不创建() {
        String name = PREFIX + "no_pwd";
        initializer.initialize(name, null);

        assertEquals(0, userService.list(new UserQuery(name, null, null, 1, 20)).total(),
                "未配置管理员初始密码时不得创建弱口令账号");
    }

    @Test
    void 同名已在位_幂等不重复创建() {
        String name = PREFIX + "twice";
        initializer.initialize(name, "SeedPass!2026a");
        initializer.initialize(name, "SeedPass!2026a");

        assertEquals(1, userService.list(new UserQuery(name, null, null, 1, 20)).total(),
                "重复初始化不重复创建");
    }

    @Test
    void 表非空且缺席_仍然创建种子管理员_存量行零触碰() {
        String demoUser = PREFIX + "demo_" + System.nanoTime();
        String gapAdmin = PREFIX + "gap_admin";
        userService.create(null, new UserCreateInput(demoUser, "Demo#Pass1", "演示账号", null, null, 2));
        assertTrue(!userService.existsByUsername(gapAdmin), "前置=种子用户名缺席");
        assertTrue(!userService.isEmpty(), "前置=表非空");

        initializer.initialize(gapAdmin, "SeedPass!2026a");

        UserInfo admin = userService.authenticate(gapAdmin, "SeedPass!2026a");
        assertEquals(4, admin.role(), "缺席即建仍为系统管理员（role=4）");
        assertTrue(userService.list(new UserQuery(demoUser, null, null, 1, 20)).total() >= 1,
                "存量演示账号零触碰");
        initializer.initialize(gapAdmin, "SeedPass!2026a");
        assertEquals(1, userService.list(new UserQuery(gapAdmin, null, null, 1, 100)).total(),
                "已在位即跳过");
    }

    @Test
    void 种子在位_ensureSeedAdmin_幂等不重复() {
        // R37: the shipped admin row is baseline data and tests never delete
        // it, so ensureSeedAdmin exercises its idempotent-skip branch against
        // the real factory admin here. The absent-rebuild branch runs through
        // the same initialize path (DEFAULT_ADMIN_USERNAME delegation) and is
        String seedPwd = System.getenv("ADMIN_INIT_PASSWORD");
        assumeTrue(seedPwd != null && !seedPwd.isBlank(), "需要 ADMIN_INIT_PASSWORD 环境变量（.env）");

        assertEquals(1L, adminRowCount(), "出厂管理员基线恰 1 行");
        initializer.ensureSeedAdmin();
        assertEquals(1L, adminRowCount(), "ensureSeedAdmin 幂等不重复创建");
    }

    private long adminRowCount() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user WHERE username = 'admin' AND deleted = 0", Long.class);
        return n == null ? 0L : n;
    }
}
