package com.sixbean.testpike.admin.explore.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ExploreDomainPermissionWiringTest {

    
    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = ExploreController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "explore 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void explore域三读路径纯豁免创建链路挂键不变() {
        assertEquals("perm:script:explore",
                permOf("create", com.sixbean.testpike.admin.explore.api.dto.ExploreCreateRequest.class),
                "创建链路=RUN 族键+角色闸双闸面，本包不动");
        assertNull(permOf("list", long.class, int.class, int.class, String.class, String.class),
                "会话列表=K6 项目成员闸，挂 perm:script:explore 缩只读成员=漂移");
        assertNull(permOf("detail", long.class),
                "会话详情=先取会话按 projectId 守（K6/H1 闸），同理禁挂键");
        assertNull(permOf("screenshot", long.class, int.class),
                "单步取证预签名=项目成员（02 §2.18），同理禁挂键");

        String[][] exempt = {
                {"GET", "/api/v1/explore/sessions"},
                {"GET", "/api/v1/explore/sessions/1"},
                {"GET", "/api/v1/explore/sessions/1/steps/2/screenshot"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "explore 域豁免链路未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void 豁免模板段不外溢不误伤相邻面() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/explore/sessions/recycle-bin"),
                "{id} 段数字语义：字面兄弟路径不得被误豁免");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/explore/sessions/1/steps/2/download"),
                "字面尾段禁被模板吞：screenshot 之外的兄弟段不在豁免面");
    }

    
    @Test
    void t210转用例链路挂键与删除链路创建人闸豁免() {
        assertEquals("perm:script:explore",
                permOf("toCases", long.class,
                        com.sixbean.testpike.admin.explore.api.dto.ExploreToCasesRequest.class),
                "to-cases=RUN 族键+角色闸双闸面，与创建链路同形（02 L916 权限列同词）");
        assertNull(permOf("delete", long.class),
                "DELETE=创建人/ADMIN 角色闸：键命中集≠创建人集双向漂移禁挂（L917，skill submit 先例）");
        assertTrue(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/explore/sessions/1"),
                "DELETE 链路豁免未登记（裸缺口）");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/explore/sessions/recycle-bin"),
                "{id}=数字语义：字面兄弟段禁被吞（坑29 同律）");
    }

    @Test
    void 豁免面方法敏感与尾斜杠边界() {
        assertTrue(PublicEndpointRegistry.isPublic("GET", "/api/v1/explore/sessions"),
                "列表链路=GET 豁免");
        assertFalse(PublicEndpointRegistry.isPublic("DELETE", "/api/v1/explore/sessions"),
                "方法敏感：列表只 GET（POST 创建链路=挂键面不在豁免面）");
        assertFalse(PublicEndpointRegistry.isPublic("POST", "/api/v1/explore/sessions"),
                "创建链路挂 perm:script:explore 键，禁被豁免面静默吞掉");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/explore/sessions/1/"),
                "尾斜杠形态不得被整路径模板误豁免（Spring 路由与豁免面口径一致）");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/explore/sessions/1/steps/2"),
                "禁外溢：screenshot 后缀段必填，/{id}/steps/{seq} 裸形不在豁免面");
    }
}
