package com.sixbean.testpike.admin.rbac.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.StubCoreRoleClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;

import jakarta.servlet.DispatcherType;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PermissionInterceptorTest {

    private final StubCoreRoleClient client = new StubCoreRoleClient();
    private final PermissionInterceptor interceptor = new PermissionInterceptor(client);

    private static final class SampleHandler {
        @RequirePermission("role:manage")
        public void manageRole() {
        }

        @RequirePermission("config:manage")
        public void manageConfig() {
        }

        @RequirePermission("perm:skill:approve")
        public void approveSkill() {
        }

        public void noAnnotation() {
        }
    }

    private static HandlerMethod method(String name) throws NoSuchMethodException {
        return new HandlerMethod(new SampleHandler(), SampleHandler.class.getMethod(name));
    }

    private void login(long userId, String... roles) {
        UserContext.set(new CurrentUser(userId, "u", "U", "D", List.of(roles),
                System.currentTimeMillis() + 3600_000, null));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void 有权限点_放行() throws Exception {
        login(1L, "ADMIN");
        client.perms(Set.of("role:manage"));
        assertDoesNotThrow(() -> interceptor.preHandle(null, null, method("manageRole")),
                "拥有 role:manage 权限点放行（TC-K4-01 授权 200 语义）");
    }

    @Test
    void 无权限点_403人话指明权限点() throws Exception {
        login(2L, "TESTER");
        client.perms(Set.of("executor:view"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> interceptor.preHandle(null, null, method("manageRole")),
                "无 role:manage → 403（TC-K4-01 无权限 403 语义）");
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("role:manage"), "人话指明所需权限点: " + ex.getMessage());
    }

    @Test
    void 多权限点_拥有其一即可() throws Exception {
        login(3L, "TEST_LEAD");
        client.perms(Set.of("config:manage", "case:review"));
        assertDoesNotThrow(() -> interceptor.preHandle(null, null, method("manageConfig")),
                "权限聚合含 config:manage 放行");
    }

    @Test
    void 新键注解_有键放行_无键403人话含中文名与键() throws Exception {
        login(5L, "TEST_LEAD");
        client.perms(Set.of("perm:skill:approve"));
        assertDoesNotThrow(() -> interceptor.preHandle(null, null, method("approveSkill")),
                "新键命中放行（perm: 命名空间直通裁决）");
        client.perms(Set.of("perm:case:case-generate"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> interceptor.preHandle(null, null, method("approveSkill")));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("perm:skill:approve"), "人话含权限键: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("SKILL 审批流"), "目录在册键附中文名: " + ex.getMessage());
    }

    @Test
    void 未标注注解_放行() throws Exception {
        login(4L, "DEVELOPER");
        client.perms(Set.of());
        assertDoesNotThrow(() -> interceptor.preHandle(null, null, method("noAnnotation")),
                "未标注 @RequirePermission = 登录即可访问");
    }

    @Test
    void 未登录_401() throws Exception {
        client.perms(Set.of("role:manage"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> interceptor.preHandle(null, null, method("manageRole")),
                "无 UserContext → 40101");
        assertEquals(40101, ex.getCode());
    }

    @Test
    void 异步派发不重复鉴权_sse收口派发放行() throws Exception {
        // SseEmitter completion re-dispatches the request with DispatcherType.ASYNC.
        // JwtAuthFilter (OncePerRequestFilter) does not run again on that dispatch, so
        // UserContext is empty even though the first dispatch authenticated — a second
        // check would throw 40101 and the global handler would then fail to write JSON
        // onto the preset text/event-stream (L3 real defect: 'No converter for Response').
        MockHttpServletRequest async = new MockHttpServletRequest();
        async.setDispatcherType(DispatcherType.ASYNC);
        client.perms(Set.of("role:manage"));
        assertDoesNotThrow(() -> interceptor.preHandle(async, null, method("manageRole")),
                "ASYNC 派发=首次派发已鉴权，二次鉴权必误判未登录");
        // REQUEST dispatch keeps the original semantics: still 40101 without a
        // login — this ASYNC exemption never widens access.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(DispatcherType.REQUEST);
        assertThrows(ServiceException.class,
                () -> interceptor.preHandle(request, null, method("manageRole")));
    }
}
