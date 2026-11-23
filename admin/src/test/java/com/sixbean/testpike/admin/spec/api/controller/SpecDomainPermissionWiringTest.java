package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SpecDomainPermissionWiringTest {

    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            com.sixbean.testpike.admin.rbac.app.component.RequirePermission ann =
                    m.getAnnotation(com.sixbean.testpike.admin.rbac.app.component.RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("spec 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void spec域四端点零注解纯豁免域() {
        assertNull(permOf(ApiSpecController.class, "upload", long.class, String.class,
                org.springframework.web.multipart.MultipartFile.class),
                "契约上传=需求输入写路径（assertCanWriteRequirement 角色闸）");
        assertNull(permOf(ApiSpecController.class, "list", long.class, int.class, int.class),
                "契约列表=成员只读路径");
        assertNull(permOf(ApiSpecController.class, "history", long.class, String.class),
                "版本链=成员只读路径");
        assertNull(permOf(ApiSpecController.class, "get", long.class),
                "契约详情=行归属反查成员链路");
        assertNull(permOf(ApiSpecController.class, "fetchUrl",
                        com.sixbean.testpike.admin.spec.api.dto.SpecUrlFetchRequest.class),
                "URL 拉取=需求输入写路径（assertCanWriteRequirement 角色闸，同上传面）");
        assertNull(permOf(ApiSpecController.class, "createSubscription",
                        com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionSaveRequest.class),
                "订阅新建=需求输入写路径（assertCanWriteRequirement，同上传面）");
        assertNull(permOf(ApiSpecController.class, "listSubscriptions",
                        long.class, int.class, int.class),
                "订阅列表=成员只读路径");
        assertNull(permOf(ApiSpecController.class, "updateSubscription",
                        long.class, com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionSaveRequest.class),
                "订阅编辑=行归属反查需求写路径");
        assertNull(permOf(ApiSpecController.class, "deleteSubscription", long.class),
                "订阅删除=行归属反查需求写路径");

        String[][] exempt = {
                {"POST", "/api/v1/api-specs"},
                {"POST", "/api/v1/api-specs/fetch-url"},
                {"GET", "/api/v1/api-specs"},
                {"GET", "/api/v1/api-specs/history"},
                {"GET", "/api/v1/api-specs/7"},
                {"POST", "/api/v1/api-specs/subscriptions"},
                {"GET", "/api/v1/api-specs/subscriptions"},
                {"PUT", "/api/v1/api-specs/subscriptions/7"},
                {"DELETE", "/api/v1/api-specs/subscriptions/7"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "spec 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }
}
