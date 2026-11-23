package com.sixbean.testpike.admin.auth.app.component;

import java.util.List;


public final class LocalRoleMapper {

    public static final String ROLE_TESTER = "TESTER";
    public static final String ROLE_TEST_LEAD = "TEST_LEAD";
    public static final String ROLE_DEVELOPER = "DEVELOPER";
    public static final String ROLE_ADMIN = "ADMIN";

    private LocalRoleMapper() {
    }

    
    public static List<String> toRoleNames(int role) {
        return switch (role) {
            case 1 -> List.of(ROLE_TESTER);
            case 2 -> List.of(ROLE_TEST_LEAD);
            case 3 -> List.of(ROLE_DEVELOPER);
            case 4 -> List.of(ROLE_ADMIN);
            default -> List.of();
        };
    }
}
