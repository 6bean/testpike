package com.sixbean.testpike.admin.delreq.api.controller;

import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestCreateRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestRejectRequest;
import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class DeleteRequestDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = DeleteRequestController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "delreq 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void delreq域四条链路纯豁免零挂键() {
        assertNull(permOf("list", long.class, Integer.class, int.class, int.class,
                        String.class, String.class),
                "列表=K6 项目成员/H1 隔离闸，挂 RUN 键缩 developer 只读成员禁挂");
        assertNull(permOf("create", DeleteRequestCreateRequest.class),
                "提交申请=用例删除同权限面（负责人/ADMIN 且非开发），挂键人话劣化保留 guard");
        assertNull(permOf("approve", long.class),
                "审批通过=assertCanManage（ADMIN∪负责人），禁借 S2 APPROVE 近亲键（放大/缩面皆漂移）");
        assertNull(permOf("reject", long.class, DeleteRequestRejectRequest.class),
                "审批拒绝=同上，core requireHandler 双层兜底");

        String[][] exempt = {
                {"GET", "/api/v1/delete-requests"},
                {"POST", "/api/v1/delete-requests"},
                {"POST", "/api/v1/delete-requests/7/approve"},
                {"POST", "/api/v1/delete-requests/7/reject"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "delreq 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不外溢不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/delete-requests/abc/approve"),
                "{id} 模板禁吞字面段（非数字永不路由到 handler=豁免面歧义盲区）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/delete-requests/7/approve"),
                "方法敏感：approve/reject 面只 POST");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/delete-requests"),
                "方法敏感：集合面只 GET/POST（delreq 无 DELETE 端点）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/delete-requests/7/restore"),
                "未登记动作段不得被 {id} 模板外溢豁免");
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/reports/calibers"),
                "既有豁免面不受本域接线影响（基线反证）");
    }
}
