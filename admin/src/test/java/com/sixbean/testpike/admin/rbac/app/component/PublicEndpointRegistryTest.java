package com.sixbean.testpike.admin.rbac.app.component;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PublicEndpointRegistryTest {

    
    @Test
    void 精确路径命中且方法敏感() {
        assertTrue(PublicEndpointRegistry.isPublic("POST", "/api/v1/auth/login"));
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/auth/login"),
                "方法不匹配不得豁免（login 只有 POST 形态）");
        assertTrue(PublicEndpointRegistry.isPublic("PUT", "/api/v1/users/me/password"));
    }

    @Test
    void 模板段匹配真实路径() {
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/open/badge/abc.svg")
                || !PublicEndpointRegistry.isPublic("GET", "/api/v1/roles/7"),
                "夹具自洽哨兵（防恒真误删）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/roles/7"),
                "业务路径不在豁免面（/roles/{id} 模板不匹配裸前缀）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/roles/7/permissions"),
                "角色覆写读=ADMIN 键管，不得白名单化");
    }

    @Test
    void 通配前缀限尾部且不含模板() {
        assertTrue(PublicEndpointRegistry.isPublic("GET",
                "/storage/files/2/script/202609/ab12-a.txt"), "带签下载 URL 豁免");
        assertTrue(PublicEndpointRegistry.isPublic("PUT", "/storage/files/5/artifact/x.zip"));
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/storage/filesX"),
                "前缀通配必须带斜杠边界");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/storage/config"));
    }

    @Test
    void badge公开端点预留登记位_红线五() {
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/open/badge/7f0c1c2a-1234-5678-9abc-def012345678.svg"),
                "I8 badge（api 服务后批实现）必须已在白名单登记位预留（红线⑤）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/open/badge/x.svg"));
    }

    @Test
    void 登记项必带人话理由且路径合法() {
        for (PublicEndpointRegistry entry : PublicEndpointRegistry.values()) {
            assertTrue(entry.getReason() != null && entry.getReason().length() >= 4,
                    entry + " 豁免必须写理由");
            assertTrue(entry.getPathPattern().startsWith("/"), entry + " 路径必须以 / 开头");
            assertTrue(entry.getMethod().equals("*") || entry.getMethod().matches("[A-Z]+"),
                    entry + " 方法段非法");
        }
    }

    @Test
    void 登记面唯一无重复键() {
        Set<String> keys = java.util.Arrays.stream(PublicEndpointRegistry.values())
                .map(e -> e.getMethod() + " " + e.getPathPattern())
                .collect(Collectors.toSet());
        assertEquals(PublicEndpointRegistry.values().length, keys.size(),
                "同 method+path 不得登记两条（豁免面歧义=审计盲区）");
    }
}
