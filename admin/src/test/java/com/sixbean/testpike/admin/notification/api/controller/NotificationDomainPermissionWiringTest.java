package com.sixbean.testpike.admin.notification.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class NotificationDomainPermissionWiringTest {

    
    private static final String KEY = "perm:ux:notify-center";

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = NotificationController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "notification 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void notification域三条链路一挂两豁免零漂移() {
        assertEquals(KEY, permOf("list", Integer.class, int.class, int.class, String.class,
                        String.class, String.class),
                "站内信列表=O6 贴语义 VIEW 族键，挂键恒放行承 coverage 手法");

        assertNull(permOf("markRead", long.class),
                "标记已读=本人自助动作链路，禁借 P5 偏好键（ai 域④近亲键先例）");
        assertNull(permOf("markAllRead"),
                "全部已读=本人自助动作链路，同上");

        assertTrue(PublicEndpointRegistry.isPublic("PUT", "/api/v1/notifications/7/read"),
                "标记已读未登记豁免（裸缺口）");
        assertTrue(PublicEndpointRegistry.isPublic("PUT", "/api/v1/notifications/read-all"),
                "全部已读未登记豁免（裸缺口）");

        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/notifications"),
                "挂键链路禁登记豁免（双通道）");
    }

    @Test
    void O6键族反证钉死四模板恒命中证据链() {
        PermissionCatalog o6 = PermissionCatalog.fromKey(KEY);
        assertEquals(PermActionFamily.VIEW, o6.family(), "O6 键族=VIEW（证据链勿改）");
        assertEquals("O6", o6.functionId(), "O6 键映射功能点勿改");
        for (RoleTemplate t : RoleTemplate.values()) {
            assertTrue(t.baselineKeys().contains(KEY),
                    t.name() + " 对 O6 键应恒命中（VIEW 族四模板基线均含）");
        }
    }

    @Test
    void 豁免模板段不外溢不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("PUT", "/api/v1/notifications/abc/read"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/notifications/7/read"),
                "方法敏感：/read 面只 PUT");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/notifications/read-all"),
                "方法敏感：read-all 只 PUT（GET 形态不得误豁免）");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/notifications/7"),
                "notification 域无 DELETE 端点，不得误豁免");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
