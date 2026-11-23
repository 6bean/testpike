package com.sixbean.testpike.core.testsupport;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.user.domain.component.UserSeedInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@SpringBootTest(classes = CoreApplication.class)
class SeedAdminGuardListenerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void 空表触发守卫重建种子管理员() {
        jdbcTemplate.execute("DELETE FROM tp_user_role");
        jdbcTemplate.execute("DELETE FROM tp_user");
        assertEquals(0, countUsers(), "前置：表应为空");

        new SeedAdminGuardListener().afterTestClass(TestContextStub.of(applicationContext));

        Integer admin = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user WHERE username = ? AND deleted = 0",
                Integer.class, UserSeedInitializer.DEFAULT_ADMIN_USERNAME);
        assertNotNull(admin);
        assertEquals(1, admin, "守卫应重建种子 admin");
        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM tp_user WHERE username = 'admin'", String.class);
        assertNotNull(hash, "BCrypt 哈希须写入（否则无法登录）");
    }

    @Test
    void 表非空守卫不干预已有账号() {
        applicationContext.getBean(UserSeedInitializer.class).ensureSeedAdmin();
        long before = countUsers();
        new SeedAdminGuardListener().afterTestClass(TestContextStub.of(applicationContext));
        assertEquals(before, countUsers(), "非空表守卫不动（幂等）");
    }

    @Test
    void 表非空但admin缺席守卫仍重建() {
        String keeper = "guard_keeper_" + System.nanoTime();
        UserSeedInitializer seed = applicationContext.getBean(UserSeedInitializer.class);
        try {
            seed.initialize(keeper, "Keeper#Pass1");
            jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN "
                    + "(SELECT id FROM tp_user WHERE username = ?)",
                    UserSeedInitializer.DEFAULT_ADMIN_USERNAME);
            jdbcTemplate.update("DELETE FROM tp_user WHERE username = ?",
                    UserSeedInitializer.DEFAULT_ADMIN_USERNAME);

            new SeedAdminGuardListener().afterTestClass(TestContextStub.of(applicationContext));

            Integer admin = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_user WHERE username = ? AND deleted = 0",
                    Integer.class, UserSeedInitializer.DEFAULT_ADMIN_USERNAME);
            assertEquals(1, admin, "非空表+admin 缺席=守卫须重建");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN "
                    + "(SELECT id FROM tp_user WHERE username = ?)", keeper);
            jdbcTemplate.update("DELETE FROM tp_user WHERE username = ?", keeper);
        }
    }

    private long countUsers() {
        Long c = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_user", Long.class);
        return c == null ? 0 : c;
    }
}
