package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.api.dto.SsoUser;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public final class MockUserCatalog {

    private final Map<String, SsoUser> byUsername = new LinkedHashMap<>();
    private final List<SsoUser> all;

    public MockUserCatalog() {
 SsoUser admin = new SsoUser(1L, "tester", "Tester", "测试部",
                List.of("ADMIN", "TEST_LEAD"), "tester@testpike.local", null);
        SsoUser tester = new SsoUser(2L, "tester01", "测试小李", "测试部",
                List.of("TESTER"), "tester01@testpike.local", null);
        SsoUser developer = new SsoUser(3L, "dev01", "开发小王", "开发部",
                List.of("DEVELOPER"), "dev01@testpike.local", null);
        byUsername.put(admin.username(), admin);
        byUsername.put(tester.username(), tester);
        byUsername.put(developer.username(), developer);
        all = List.copyOf(byUsername.values());
    }

    
    public SsoUser findByUsername(String username) {
        return byUsername.get(username);
    }

    public List<SsoUser> all() {
        return all;
    }
}
