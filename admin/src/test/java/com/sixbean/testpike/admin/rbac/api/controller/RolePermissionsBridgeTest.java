package com.sixbean.testpike.admin.rbac.api.controller;

import com.sixbean.testpike.admin.rbac.api.dto.PermGroupView;
import com.sixbean.testpike.admin.rbac.api.dto.PermItemView;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideItem;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideRequest;
import com.sixbean.testpike.admin.rbac.api.dto.RolePermOverrideView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class RolePermissionsBridgeTest {

    private final CoreRoleClient client = mock(CoreRoleClient.class);
    private final RoleController roleController = new RoleController(client);
    private final PermissionCatalogController catalogController =
            new PermissionCatalogController(client);

    @Test
    void 目录分组树_透传core分组数据源() {
        List<PermGroupView> groups = List.of(new PermGroupView("case", "用例域",
                List.of(new PermItemView("perm:case:view", "用例查看", "view", "A1"))));
        when(client.permCatalogGrouped()).thenReturn(groups);
        Response<List<PermGroupView>> resp = catalogController.catalog();
        assertEquals("0", resp.getCode());
        assertEquals("用例域", resp.getData().get(0).domainLabel());
        assertEquals("A1", resp.getData().get(0).items().get(0).functionId(),
                "functionId 追溯锚=check [6/6] 同源，桥接层禁丢字段");
        verify(client).permCatalogGrouped();
    }

    @Test
    void 覆写读取_透传core稳定序() {
        when(client.permissionsOfRole(9L)).thenReturn(List.of(
                new RolePermOverrideView("perm:case:view", 1),
                new RolePermOverrideView("perm:audit:view", 2)));
        Response<List<RolePermOverrideView>> resp = roleController.permissions(9L);
        assertEquals(2, resp.getData().size());
        assertEquals(2, resp.getData().get(1).effect());
        verify(client).permissionsOfRole(9L);
    }

    @Test
    void 覆写保存_请求体直透core零值域判定() {
        RolePermOverrideRequest request = new RolePermOverrideRequest(
                List.of(new RolePermOverrideItem("perm:case:view", 2)), false);
        when(client.savePermissions(eq(9L), eq(request))).thenReturn(
                List.of(new RolePermOverrideView("perm:case:view", 2)));
        Response<List<RolePermOverrideView>> resp = roleController.savePermissions(9L, request);
        assertEquals("0", resp.getCode());
        verify(client).savePermissions(9L, request);
    }

    @Test
    void 权限链路_三端点均ADMIN_requirePermission注解() throws Exception {
        Method catalog = PermissionCatalogController.class.getMethod("catalog");
        assertAdminGuard(catalog);
        assertAdminGuard(RoleController.class.getMethod("permissions", long.class));
        assertAdminGuard(RoleController.class.getMethod("savePermissions",
                long.class, RolePermOverrideRequest.class));
    }

    @Test
    void 桥路径_对齐core_internal契约() throws Exception {
        assertNotNull(CoreRoleClient.class.getMethod("permCatalogGrouped"));
        assertNotNull(CoreRoleClient.class.getMethod("permissionsOfRole", long.class));
        assertNotNull(CoreRoleClient.class.getMethod("savePermissions",
                long.class, RolePermOverrideRequest.class));
        assertArrayEquals(new String[] {"items", "confirm"},
                java.util.Arrays.stream(RolePermOverrideRequest.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
        assertArrayEquals(new String[] {"permKey", "effect"},
                java.util.Arrays.stream(RolePermOverrideItem.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
        RequestMapping mapping = PermissionCatalogController.class.getAnnotation(RequestMapping.class);
        assertArrayEquals(new String[] {"/api/v1/permissions"}, mapping.value(),
                "02 权威路径 /permissions/catalog（ADMIN）");
        assertTrue(RoleController.class.getAnnotation(RequestMapping.class).value()[0]
                .equals("/api/v1/roles"));
    }

    private static void assertAdminGuard(Method method) {
        RequirePermission require = method.getAnnotation(RequirePermission.class);
        assertNotNull(require, method.getName() + " 必须挂权限注解（TC-K12-02 无豁免链路）");
        assertEquals("perm:system:role-manage", require.value(),
                "02 K12 权限列=ADMIN（③c-3a 注解已替换新键，legacy 语义映射=LegacyPermMapping）");
    }
}
