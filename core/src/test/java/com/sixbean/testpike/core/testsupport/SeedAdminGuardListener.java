package com.sixbean.testpike.core.testsupport;

import com.sixbean.testpike.core.user.domain.component.UserSeedInitializer;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;


public class SeedAdminGuardListener extends AbstractTestExecutionListener {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        org.springframework.context.ApplicationContext ctx;
        try {
            ctx = testContext.getApplicationContext();
        } catch (IllegalStateException e) {
            return;
        }
        // Same criterion as UserSeedInitializer (defect ledger item 11): the
        // seed admin is owed whenever the login name is absent, even on a
        // non-empty table (demo-seeded or targeted-delete scenarios).
        Integer adminRows = ctx.getBean(JdbcTemplate.class)
                .queryForObject("SELECT count(*) FROM tp_user WHERE username = ?",
                        Integer.class, UserSeedInitializer.DEFAULT_ADMIN_USERNAME);
        if (adminRows == null || adminRows == 0) {
            ctx.getBean(UserSeedInitializer.class).ensureSeedAdmin();
        }
    }
}
