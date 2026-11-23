package com.sixbean.testpike.common.rbac;

import java.util.Collection;


public final class SystemManagerRoles {

    
    public static final String ROLE_ADMIN = "ADMIN";
    
    public static final String ROLE_TEST_LEAD = "TEST_LEAD";

    private SystemManagerRoles() {
    }

    
    public static boolean isSystemManager(Collection<String> roles) {
        return roles != null && (roles.contains(ROLE_ADMIN) || roles.contains(ROLE_TEST_LEAD));
    }

    
    public static boolean isAdmin(Collection<String> roles) {
        return roles != null && roles.contains(ROLE_ADMIN);
    }
}
