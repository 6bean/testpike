package com.sixbean.testpike.core.user.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.domain.service.UserService;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;


@Component
public class UserSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeedInitializer.class);

    public static final String DEFAULT_ADMIN_USERNAME = "admin";

    private final UserService userService;

    public UserSeedInitializer(UserService userService) {
        this.userService = userService;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = System.getenv("ADMIN_INIT_PASSWORD");
        initialize(DEFAULT_ADMIN_USERNAME, password);
    }

    
    public void ensureSeedAdmin() {
        initialize(DEFAULT_ADMIN_USERNAME, System.getenv("ADMIN_INIT_PASSWORD"));
    }

    
    public void initialize(String username, String password) {
        if (userService.existsByUsername(username)) {
            return;
        }
        // Seed guarantee is "an operator can log in as admin after first boot",
        // not "the very first row of an empty table". The old table-empty check
        // silently skipped seeding once factory demo rows landed first (defect
        // ledger item 11: fresh installs and cloned slot DBs had no loginable
        // admin). Re-checking only the username keeps this idempotent and
        // touches no other user row.
        if (password == null || password.isBlank()) {
            log.warn("未配置 ADMIN_INIT_PASSWORD，跳过系统管理员种子创建；"
                    + "请在 .env 设置 ADMIN_INIT_PASSWORD 后重启（本地认证默认模式必需）");
            return;
        }
        long id;
        try {
            id = userService.create(null, new UserCreateInput(
                    username, password, "系统管理员", null, null, 4));
        } catch (ServiceException ex) {
            // Check-then-act on a shared database: a concurrent seed guard in
            // another JVM won the race and the account now exists — the
            // guarantee is satisfied either way, so yield instead of failing.
            log.info("系统管理员种子创建让位（并发实例已建）：{}", ex.getMessage());
            return;
        }
        log.info("已初始化系统管理员账号 {}（id={}，密码来自 ADMIN_INIT_PASSWORD，请登录后尽快修改）",
                username, id);
    }
}
