package com.sixbean.testpike.admin.audit.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.app.component.PermissionInterceptor;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.StubCoreRoleClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


class AuditLogGuardTest {

    private final StubCoreRoleClient client = new StubCoreRoleClient();
    private final PermissionInterceptor interceptor = new PermissionInterceptor(client);

    private static final class AuditHandler {
        @RequirePermission("perm:detail:audit-view")
        public void viewAudit() {
        }
    }

    private static HandlerMethod auditMethod() throws NoSuchMethodException {
        return new HandlerMethod(new AuditHandler(), AuditHandler.class.getMethod("viewAudit"));
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
    void 有新审计查看键_放行() throws Exception {
        login(2L, "TEST_LEAD");
        client.perms(Set.of("perm:detail:audit-view"));
        assertDoesNotThrow(() -> interceptor.preHandle(null, null, auditMethod()),
                "拥有 perm:detail:audit-view 权限点可查审计日志");
    }

    @Test
    void 无auditView权限_403人话() throws Exception {
        login(3L, "TESTER");
        client.perms(Set.of("case:review"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> interceptor.preHandle(null, null, auditMethod()),
                "普通成员查审计 → 403（IT-M1-01）");
        assertEquals(40301, ex.getCode());
    }

    @Test
    void 系统管理员_全量兜底放行() throws Exception {
        login(1L, "ADMIN");
        client.perms(Set.of("perm:detail:audit-view"));
        assertDoesNotThrow(() -> interceptor.preHandle(null, null, auditMethod()),
                "内置系统管理员全量权限兜底可查审计");
    }
}
