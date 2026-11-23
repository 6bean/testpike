package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RoleDeclarationSupportTest {

    private final RoleDeclarationSupport support = new RoleDeclarationSupport(new ObjectMapper());

    @Test
    void 无roles段_空列表零漂移() {
        String actionJson = """
                {"version":"v2","caseId":"TC-001",
                 "steps":[{"action":"goto","target":{"url":"http://x"}}]}
                """;
        assertEquals(List.of(), support.roles(actionJson), "存量动作 JSON 无 roles 段=空（零迁移护栏）");
    }

    @Test
    void 合法roles_四档绑定解析() {
        String actionJson = """
                {"version":"v2","caseId":"TC-002",
                 "roles":[{"name":"买家","binding":"per-vu","credential":"login"},
                          {"name":"卖家","binding":"shared"},
                          {"name":"优惠券","binding":"pool(5)"},
                          {"name":"访客","binding":"none"}],
                 "steps":[]}
                """;
        List<RoleDeclaration> roles = support.roles(actionJson);
        assertEquals(4, roles.size());
        assertEquals("买家", roles.get(0).name());
        assertEquals("per-vu", roles.get(0).binding());
        assertEquals("login", roles.get(0).credential());
        assertEquals("shared", roles.get(1).binding());
        assertTrue(roles.get(1).credential() == null, "credential 缺省=null");
        assertEquals("pool(5)", roles.get(2).binding(), "pool(N) 原样承载（N 值域闸在保存链校验器）");
        assertEquals("none", roles.get(3).binding());
    }

    @Test
    void 脏JSON_空列表兜底不炸派发() {
        assertEquals(List.of(), support.roles("不是JSON{{{"), "脏 JSON=无声明兜底");
        assertEquals(List.of(), support.roles(null));
        assertEquals(List.of(), support.roles(""));
    }

    @Test
    void roles非数组_空列表兜底() {
        String actionJson = """
                {"version":"v2","caseId":"TC-003","roles":"买家","steps":[]}
                """;
        assertEquals(List.of(), support.roles(actionJson),
                "非数组=解析不出（严格拒由保存链校验器承担）");
    }
}
