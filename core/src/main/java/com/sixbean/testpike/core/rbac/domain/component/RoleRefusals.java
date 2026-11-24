package com.sixbean.testpike.core.rbac.domain.component;

/**
 * Single-source refusal wording for the role create/update/delete and
 * permission-override doors under the R63 smart-guidance contract: every
 * sentence echoes the offending submitted value, quotes the on-screen repair
 * entry in 「」, and keeps the legacy capability clause contiguous so the
 * existing role-service assertions hold.
 *
 * <p>Quoted literals must match the shipped frontend wording (the role
 * management page under the system menu edits roles and permission
 * overrides). Keep them in sync when the screens change.
 */
public final class RoleRefusals {

    /** Repair entry quoted for role form, delete and permission-override refusals. */
    public static final String ROLE_ENTRY = "「角色管理」页";

    private RoleRefusals() {
    }

    public static String roleNotFound(long roleId) {
        return "角色不存在（提交角色 id= " + roleId + "）；请到" + ROLE_ENTRY
                + "刷新列表确认角色后重试，列表没有该行说明角色已被删除";
    }

    public static String codeExists(String code) {
        return "角色编码 " + code + " 已存在，请更换；可在" + ROLE_ENTRY
                + "的新建角色表单修改编码（建议加业务域后缀，如 " + code + "-ops）后重新保存";
    }

    public static String builtinCodeLocked(String code) {
        return "内置角色不可修改角色编码（当前编码= " + code + "）；可在" + ROLE_ENTRY
                + "的行编辑中修改名称、描述与数据范围，需要不同编码请新建自定义角色";
    }

    public static String builtinDeleteBlocked(String name) {
        return "内置角色（" + name + "）不可删除；如需收敛其权限请在" + ROLE_ENTRY
                + "调整该角色的权限覆写，或将「状态」改为停用";
    }

    public static String permCodeUnknown(String code) {
        return "权限点编码 " + code + " 不存在，请从权限点目录中选择；目录位于" + ROLE_ENTRY
                + "的行内权限配置面板，请点选条目而不是手工键入编码（合法编码以 perm: 开头）";
    }

    public static String emptyOverridesNeedConfirm(long roleId) {
        return "空覆写集=清空该角色全部覆写并回退模板基线，请携带 confirm=true 确认此操作"
                + "（角色 id= " + roleId + "）；可在" + ROLE_ENTRY
                + "的权限覆写面板先添加保留项，或勾选清空确认后提交";
    }

    public static String effectOutOfRange(int effect, String permKey) {
        return "权限覆写 effect 仅支持 1=allow / 2=deny（收到 " + effect + "，键 " + permKey
                + "）；请在" + ROLE_ENTRY + "的权限覆写编辑器把该项改为 1=允许 或 2=拒绝后保存";
    }

    public static String allowDenyConflict(String permKey) {
        return "权限键 " + permKey + " 同时出现 allow 与 deny 覆写，请二选一；请在"
                + ROLE_ENTRY + "的权限覆写编辑器删除其中一条后保存";
    }

    public static String roleNameMissing(String submittedName) {
        return "角色名不能为空（本次提交长度= " + (submittedName == null ? 0 : submittedName.length())
                + "）；请在" + ROLE_ENTRY + "的新建/编辑角色表单填写角色名称后保存";
    }

    public static String roleCodeInvalid(String submittedCode) {
        return "角色编码不能为空且不超过 50 字符（本次提交长度= "
                + (submittedCode == null ? 0 : submittedCode.trim().length())
                + "）；请在" + ROLE_ENTRY + "的新建/编辑角色表单修改编码后保存";
    }

    public static String dataScopeInvalid(int scope) {
        return "数据范围仅支持 1=全部项目 2=项目内 3=仅本人（收到 " + scope
                + "）；请在" + ROLE_ENTRY + "的角色表单「数据范围」下拉中重新选择";
    }

    public static String statusInvalid(int status) {
        return "状态仅支持 1=启用 / 0=停用（收到 " + status + "）；请在" + ROLE_ENTRY
                + "的角色表单「状态」中选择启用或停用后保存";
    }
}
