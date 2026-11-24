package com.sixbean.testpike.core.user.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 contract for the account create/update/password/profile/delete/import
 * doors and the login guard: every refusal echoes the offending submitted
 * value, names the on-screen repair entry in 「」, and keeps the legacy
 * capability clause contiguous so the existing user-service and lockout
 * assertions stay green.
 */
class UserRefusalsL2Test {

    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    private static void assertNamesEntry(String message) {
        assertTrue(message.contains("「用户管理」"),
                "message must name the on-screen repair entry. actual: " + message);
    }

    @Test
    void usernameInvalidEchoesSubmittedLength() {
        String msg = UserRefusals.usernameInvalid("   ");
        assertKeepsLegacy(msg, "登录名不能为空且不超过 50 字符");
        assertTrue(msg.contains("本次提交长度"), "must echo the submitted length. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void usernameTooLongEchoesSubmittedLength() {
        String msg = UserRefusals.usernameInvalid("u".repeat(61));
        assertKeepsLegacy(msg, "50 字符");
        assertTrue(msg.contains("61"), "must echo the over-length size. actual: " + msg);
    }

    @Test
    void loginNameExistsEchoesNameAndSuggestsSuffix() {
        String msg = UserRefusals.loginNameExists("qa-lead");
        assertKeepsLegacy(msg, "已存在，请更换");
        assertTrue(msg.contains("qa-lead"), "must echo the conflicting login name. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void statusInvalidEchoesReceivedValueAndNamesSwitch() {
        String msg = UserRefusals.statusInvalid(7);
        assertKeepsLegacy(msg, "状态仅支持 1=启用 / 0=停用");
        assertTrue(msg.contains("收到 7"), "must echo the received status. actual: " + msg);
        assertTrue(msg.contains("「状态」"), "must name the status control. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void oldPasswordMismatchPointsToResetDoor() {
        String msg = UserRefusals.oldPasswordMismatch();
        assertKeepsLegacy(msg, "原密码不正确");
        assertTrue(msg.contains("「个人中心」"), "must name the self-service tab. actual: " + msg);
        assertTrue(msg.contains("重置密码"), "must offer the administrator reset route. actual: " + msg);
    }

    @Test
    void newPasswordSameAsOldQuotesPasswordRule() {
        String msg = UserRefusals.newPasswordSameAsOld();
        assertKeepsLegacy(msg, "新密码不能与原密码相同");
        assertTrue(msg.contains("8~64"), "must restate the password rule. actual: " + msg);
        assertTrue(msg.contains("「个人中心」"), "must name the self-service tab. actual: " + msg);
    }

    @Test
    void nicknameTooLongEchoesLengthAndLimit() {
        String msg = UserRefusals.nicknameTooLong(101);
        assertKeepsLegacy(msg, "不能超过 100 字符");
        assertTrue(msg.contains("101"), "must echo the submitted length. actual: " + msg);
        assertTrue(msg.contains("「个人中心」"), "must name the self-service page. actual: " + msg);
        assertTrue(msg.contains("「昵称」"), "must name the form field. actual: " + msg);
    }

    @Test
    void avatarUrlTooLongEchoesLengthAndLimit() {
        String msg = UserRefusals.avatarUrlTooLong(233);
        assertKeepsLegacy(msg, "不能超过 200 字符");
        assertTrue(msg.contains("233"), "must echo the submitted length. actual: " + msg);
        assertTrue(msg.contains("「个人中心」"), "must name the self-service page. actual: " + msg);
    }

    @Test
    void deleteSelfForbiddenPointsToRoleHandover() {
        String msg = UserRefusals.deleteSelfForbidden(42L);
        assertKeepsLegacy(msg, "不能删除当前登录账号");
        assertKeepsLegacy(msg, "请先移交管理员职责");
        assertTrue(msg.contains("42"), "must echo the account id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void retryRowsEmptyPointsToImportResultTable() {
        String msg = UserRefusals.retryRowsEmpty();
        assertKeepsLegacy(msg, "行号不能为空");
        assertTrue(msg.contains("批量导入"), "must name the import dialog. actual: " + msg);
        assertTrue(msg.contains("重试失败项"), "must name the retry button. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void userNotFoundEchoesIdAndSuggestsReload() {
        String msg = UserRefusals.userNotFound(777L);
        assertKeepsLegacy(msg, "用户不存在或已被删除");
        assertTrue(msg.contains("777"), "must echo the user id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void accountLockedEchoesRemainingMinutesAndNamesUnlockDoor() {
        String msg = UserRefusals.accountLocked(12);
        assertKeepsLegacy(msg, "账号已锁定");
        assertTrue(msg.contains("分钟"), "must keep the remaining-time unit. actual: " + msg);
        assertTrue(msg.contains("12"), "must echo the remaining minutes. actual: " + msg);
        assertTrue(msg.contains("testpike.login.lock-minutes"),
                "must name the lock duration config key. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void accountDisabledEchoesLoginNameAndNamesStatusDoor() {
        String msg = UserRefusals.accountDisabled("qa-lead");
        assertKeepsLegacy(msg, "该账号已停用");
        assertTrue(msg.contains("qa-lead"), "must echo the login name. actual: " + msg);
        assertTrue(msg.contains("「状态」"), "must name the status switch. actual: " + msg);
        assertNamesEntry(msg);
    }
}
