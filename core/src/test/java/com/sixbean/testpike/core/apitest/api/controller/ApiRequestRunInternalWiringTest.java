package com.sixbean.testpike.core.apitest.api.controller;

import com.sixbean.testpike.core.apitest.api.dto.RunResultInput;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


class ApiRequestRunInternalWiringTest {

    private static String prefixOf(Class<?> controller) {
        RequestMapping ann = controller.getAnnotation(RequestMapping.class);
        assertNotNull(ann, controller.getSimpleName() + " 必须类级 @RequestMapping 前缀");
        assertEquals(1, ann.value().length);
        return ann.value()[0];
    }

    private static Method methodOf(Class<?> controller, String name, Class<?>... params) {
        try {
            return controller.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(controller.getSimpleName() + "." + name
                    + " 签名漂移（接线纪律：与 core 服务方法一一对应）", e);
        }
    }

    @Test
    void 请求内部接口六条链路镜像服务签名() {
        assertEquals("/internal/apitest/requests", prefixOf(ApiRequestInternalController.class));
        methodOf(ApiRequestInternalController.class, "create",
                com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput.class);
        methodOf(ApiRequestInternalController.class, "update", long.class,
                com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput.class);
        methodOf(ApiRequestInternalController.class, "delete", long.class);
        methodOf(ApiRequestInternalController.class, "list", long.class);
        methodOf(ApiRequestInternalController.class, "get", long.class);
        // Promotion endpoint : POST /{id}/promote mirrors
        // PromotionService.promote(operatorId, requestId) — signature drift fails here first.
        Method promote = methodOf(ApiRequestInternalController.class, "promote", long.class);
        java.lang.reflect.ParameterizedType responseType =
                (java.lang.reflect.ParameterizedType) promote.getGenericReturnType();
        assertEquals(com.sixbean.testpike.core.apitest.api.dto.PromotionResultView.class,
                responseType.getActualTypeArguments()[0],
                "promote must return Response<PromotionResultView> (admin bridge mirrors it)");
        PostMapping promoteMapping = promote.getAnnotation(PostMapping.class);
        assertNotNull(promoteMapping, "promote 必须 @PostMapping");
        assertArrayEquals(new String[]{"/{id}/promote"}, promoteMapping.value(),
                "promote 路由必须 /{id}/promote（02 契约）");
    }

    @Test
    void 运行内部接口链路镜像服务签名() {
        assertEquals("/internal/apitest/runs", prefixOf(ApiRunInternalController.class));
        methodOf(ApiRunInternalController.class, "create",
                com.sixbean.testpike.core.apitest.api.dto.RunCreateInput.class);
        methodOf(ApiRunInternalController.class, "get", long.class);
        methodOf(ApiRunInternalController.class, "listByRequest", long.class);
        methodOf(ApiRunInternalController.class, "recordResult", long.class, RunResultInput.class);
        methodOf(ApiRunInternalController.class, "delete", long.class);
        methodOf(ApiRunInternalController.class, "clearUnpromoted", long.class);
    }

    @Test
    void 回传入参记录形状锁定() {
        assertEquals(5, RunResultInput.class.getRecordComponents().length,
                "回传 DTO 五字段形状勿漂移");
    }
}
