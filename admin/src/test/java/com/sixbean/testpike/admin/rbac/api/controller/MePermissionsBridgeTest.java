package com.sixbean.testpike.admin.rbac.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class MePermissionsBridgeTest {

    private final CoreRoleClient client = mock(CoreRoleClient.class);
    private final MePermissionsController controller = new MePermissionsController(client);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 本人权限_直透core单点_当前用户userId() {
        UserContext.set(new CurrentUser(7L, "u7", "U", "D", List.of("TESTER"),
                System.currentTimeMillis() + 3600_000, null));
        Set<String> perms = Set.of("perm:case:view", "perm:skill:approve");
        when(client.permsOfUser(7L)).thenReturn(perms);
        Response<Set<String>> resp = controller.permissions();
        assertEquals("0", resp.getCode());
        assertEquals(perms, resp.getData(), "桥零判定：core 返回集原样透传（allow/deny 解析在 core 单点）");
    }

    @Test
    void it_k12_03_admin全量展开_键数动态目录同源_非裸星号() {
        UserContext.set(new CurrentUser(1L, "admin", "A", "D", List.of("ADMIN"),
                System.currentTimeMillis() + 3600_000, null));
        Set<String> expanded = new HashSet<>(PermissionCatalog.allKeys());
        when(client.permsOfUser(1L)).thenReturn(expanded);
        Response<Set<String>> resp = controller.permissions();
        assertEquals(PermissionCatalog.allKeys().size(), resp.getData().size(),
                "展开集键数=common 目录同源动态断言（硬写数字=扩表必假红）");
        assertTrue(resp.getData().containsAll(PermissionCatalog.allKeys()));
        assertTrue(!resp.getData().contains("*"), "ADMIN=147 键展开非裸 \"*\"（IT-K12-03）");
    }

    @Test
    void 契约面_路径api_v1_me下permissions_登录即可无requirePermission() throws Exception {
        RequestMapping mapping = MePermissionsController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "02 权威路径 /api/v1/me/permissions（UserController base=/api/v1/users 载不了，勿并链路）");
        assertEquals("/api/v1/me", mapping.value()[0]);
        Method method = MePermissionsController.class.getMethod("permissions");
        GetMapping get = method.getAnnotation(GetMapping.class);
        assertNotNull(get);
        assertEquals("/permissions", get.value()[0]);
        assertNull(method.getAnnotation(RequirePermission.class),
                "本人权限码=登录即可（02 权限列）；鉴权豁免已预登记 PublicEndpointRegistry.ME_PERMISSIONS");
    }
}
