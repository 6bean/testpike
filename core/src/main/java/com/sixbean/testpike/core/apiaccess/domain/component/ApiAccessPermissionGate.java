package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessGovernanceView;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.springframework.stereotype.Component;

/**
 * The third ownership face of the pass-through door: the functional permission the serving page
 * enforces.
 *
 * <p>Why a third face is needed: the catalog derives project anchors and the door re-stamps person
 * anchors, but pages gated by {@code @RequirePermission} (RBAC functional permissions) are neither
 * — a scopeless endpoint can still be a permission-gated page. Before this component the door had
 * only two options for such entries: refuse them wholesale (users could not read from the dialog
 * what the same account reads on the page — the reported dead-letter browse), or register them
 * without a gate and serve privileged rows to every conversing operator (the wider leak). This
 * component closes both halves at the one point that serves the dialog.
 *
 * <p>Single source by construction: the permission set comes from the same
 * {@link RoleService#permsOfUser(long)} aggregation the admin interceptor runs per request (deny
 * overrides, template baseline, admin expansion — none of it re-implemented here). The verdict is
 * fail-closed: no row, no permission, unknown operator, or a resolver fault all refuse, and the
 * refusal names the permission point (with its catalog label when registered) and where it is
 * granted, so a blocked dialog turn is actionable rather than a wall.
 */
@Component
public class ApiAccessPermissionGate {

    private final RoleService roleService;

    public ApiAccessPermissionGate(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * Permission verdict for one call against one registered entry.
     *
     * @param row        the governance row for the endpoint key (null = nothing registered here,
     *                   which the policy already refused on its own grounds)
     * @param operatorId the trusted acting operator (header-carried, never model-supplied)
     * @return null when the call may proceed, the plain-words refusal otherwise
     */
    public String problem(ApiAccessGovernanceView row, Long operatorId) {
        String permission = row == null ? null : row.requiredPermission();
        if (permission == null || permission.isBlank()) {
            return null;
        }
        if (operatorId == null) {
            return "该条目与页面同权限面（需要权限点：" + describe(permission)
                    + "），本次调用没有可判定的操作人，不放行";
        }
        boolean held;
        try {
            held = roleService.permsOfUser(operatorId).contains(permission);
        } catch (RuntimeException resolverFault) {
            // Fail-closed with its own words: an unreadable permission set is not a pass.
            return "权限核验暂不可用（" + resolverFault.getClass().getSimpleName()
                    + "），该条目要求权限点 " + describe(permission) + "，本次不放行，请稍后重试";
        }
        if (held) {
            return null;
        }
        return "该条目与页面同权限面：需要权限点 " + describe(permission)
                + "。当前操作人没有该权限，对话代读与落地同样不放行；权限由系统管理员在角色管理页（/roles）授予";
    }

    /** Permission point in the page's own words: catalog label plus key, raw key when unregistered. */
    static String describe(String permission) {
        PermissionCatalog catalog = PermissionCatalog.fromKey(permission);
        return catalog == null ? permission : catalog.label() + "（" + permission + "）";
    }
}
