package com.sixbean.testpike.core.rbac.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 contract for the role create/update/delete and permission-override
 * doors: every refusal echoes the offending submitted value, names the
 * on-screen repair entry (role management page) and keeps the legacy
 * capability clause contiguous so existing assertions stay green.
 */
class RoleRefusalsL2Test {

    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    private static void assertNamesEntry(String message) {
        assertTrue(message.contains("「角色管理」"),
                "message must name the on-screen repair entry. actual: " + message);
    }

    @Test
    void roleNotFoundEchoesIdAndNamesPage() {
        String msg = RoleRefusals.roleNotFound(4001L);
        assertKeepsLegacy(msg, "角色不存在");
        assertTrue(msg.contains("4001"), "must echo the submitted role id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void duplicateCodeEchoesCodeAndSuggestsRename() {
        String msg = RoleRefusals.codeExists("dup-role");
        assertKeepsLegacy(msg, "已存在，请更换");
        assertTrue(msg.contains("dup-role"), "must echo the conflicting code. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void builtinCodeLockedNamesAlternative() {
        String msg = RoleRefusals.builtinCodeLocked("admin");
        assertKeepsLegacy(msg, "内置角色不可修改角色编码");
        assertTrue(msg.contains("admin"), "must echo the builtin code. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void builtinDeleteBlockedPointsToDisable() {
        String msg = RoleRefusals.builtinDeleteBlocked("系统管理员");
        assertKeepsLegacy(msg, "不可删除");
        assertTrue(msg.contains("系统管理员"), "must echo the role name. actual: " + msg);
        assertTrue(msg.contains("停用"), "must offer the disable alternative. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void permCodeUnknownEchoesCodeAndPointsToCatalog() {
        String msg = RoleRefusals.permCodeUnknown("not:exist");
        assertKeepsLegacy(msg, "不存在");
        assertKeepsLegacy(msg, "请从权限点目录中选择");
        assertTrue(msg.contains("not:exist"), "must echo the bad code. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void emptyOverridesNeedConfirmParameter() {
        String msg = RoleRefusals.emptyOverridesNeedConfirm(31L);
        assertKeepsLegacy(msg, "空覆写集=清空该角色全部覆写并回退模板基线");
        assertTrue(msg.contains("confirm=true"), "must name the parameter. actual: " + msg);
        assertTrue(msg.contains("31"), "must echo role id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void effectOutOfRangeEchoesValueAndKey() {
        String msg = RoleRefusals.effectOutOfRange(7, "perm:case:batch-gen");
        assertKeepsLegacy(msg, "仅支持 1=allow / 2=deny");
        assertTrue(msg.contains("7") && msg.contains("perm:case:batch-gen"),
                "must echo received effect and key. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void allowDenyConflictEchoesKeyAndPointsToEditor() {
        String msg = RoleRefusals.allowDenyConflict("perm:review:approve");
        assertKeepsLegacy(msg, "同时出现 allow 与 deny 覆写，请二选一");
        assertTrue(msg.contains("perm:review:approve"), "must echo the conflicting key. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void roleNameMissingEchoesLengthAndNamesForm() {
        String msg = RoleRefusals.roleNameMissing("   ");
        assertKeepsLegacy(msg, "角色名不能为空");
        assertTrue(msg.contains("3"), "must echo the submitted length. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void roleCodeInvalidEchoesLengthAndBound() {
        String msg = RoleRefusals.roleCodeInvalid("x".repeat(61));
        assertKeepsLegacy(msg, "角色编码不能为空且不超过 50 字符");
        assertTrue(msg.contains("61"), "must echo the submitted length. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void dataScopeInvalidEchoesSubmittedValue() {
        String msg = RoleRefusals.dataScopeInvalid(9);
        assertKeepsLegacy(msg, "数据范围仅支持 1=全部项目 2=项目内 3=仅本人");
        assertTrue(msg.contains("9"), "must echo the submitted scope. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void statusInvalidEchoesSubmittedValue() {
        String msg = RoleRefusals.statusInvalid(2);
        assertKeepsLegacy(msg, "状态仅支持 1=启用 / 0=停用");
        assertTrue(msg.contains("2"), "must echo the submitted status. actual: " + msg);
        assertNamesEntry(msg);
    }
}
