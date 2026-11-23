package com.sixbean.testpike.admin.rbac.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.rbac.infrastructure.remote.client.CoreRoleClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;


@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private final CoreRoleClient coreRoleClient;

    public PermissionInterceptor(CoreRoleClient coreRoleClient) {
        this.coreRoleClient = coreRoleClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // An SSE/async completion re-dispatches the same request with
        // DispatcherType.ASYNC. The auth filter (OncePerRequestFilter) does not run
        // again on that dispatch, so UserContext is legitimately empty here while the
        // first dispatch already authenticated and was permission-checked. Re-checking
        // would throw 40101 and the global handler would then fail writing JSON onto
        // the preset text/event-stream (L3 real defect: No converter for Response).
        if (request != null && request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        RequirePermission require = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (require == null) {
            return true;
        }
        CurrentUser user = UserContext.current();
        if (user == null) {
            throw new ServiceException("登录状态已失效，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        Set<String> perms = coreRoleClient.permsOfUser(user.userId());
        if (!perms.contains(require.value())) {
            throw new ServiceException("无操作权限（所需权限点：%s），请联系系统管理员",
                    BizErrorCode.FORBIDDEN, describeKey(require.value()));
        }
        return true;
    }

    
    private static String describeKey(String key) {
        PermissionCatalog catalog = PermissionCatalog.fromKey(key);
        return catalog == null ? key : catalog.label() + "（" + key + "）";
    }
}
