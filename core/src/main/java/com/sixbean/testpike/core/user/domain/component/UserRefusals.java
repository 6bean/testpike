package com.sixbean.testpike.core.user.domain.component;

/**
 * Single-source refusal wording for the account create, update, password,
 * profile, delete and import doors plus the login guard, under the R63
 * smart-guidance contract: every sentence echoes the offending submitted
 * value, quotes the on-screen repair entry in 「」, and keeps the legacy
 * capability clause contiguous so the existing user-service and lockout
 * assertions hold.
 *
 * <p>Password and lock-guard sentences never echo secret values — length and
 * remaining time are the only echoes. Quoted literals must match the shipped
 * frontend wording: the account doors live on the user management page, while
 * self-service password and profile editing live on the personal center page
 * with its two tabs.
 */
public final class UserRefusals {

    /** Repair entry for account doors owned by an administrator. */
    public static final String USER_ENTRY = "「用户管理」页";

    /** Repair entry for self-service password and profile editing. */
    public static final String PROFILE_ENTRY = "「个人中心」页";

    /** On-screen title of the create/edit dialog on the user management page. */
    public static final String CREATE_DIALOG = "「新增用户」";

    /** On-screen title of the administrator password reset dialog. */
    public static final String RESET_DIALOG = "「重置密码」";

    /** On-screen title of the bulk import dialog and its retry button. */
    public static final String IMPORT_DIALOG = "「批量导入」";

    public static final String RETRY_BUTTON = "「重试失败项」";

    /** On-screen tab and control labels of the personal center page. */
    public static final String INFO_TAB = "「个人资料」页签";

    public static final String PASSWORD_TAB = "「修改密码」页签";

    public static final String SAVE_BUTTON = "「保存资料」";

    /** Password shape restated in refusals so the user can fix it in one step. */
    public static final String PASSWORD_RULE = "8~64 位且同时包含字母和数字";

    /** Config key that controls how long a locked account stays locked. */
    public static final String LOCK_MINUTES_KEY = "testpike.login.lock-minutes";

    private UserRefusals() {
    }

    public static String usernameInvalid(String submitted) {
        int length = submitted == null ? 0 : submitted.trim().length();
        return "登录名不能为空且不超过 50 字符（本次提交长度= " + length
                + "）；请在" + USER_ENTRY + "的" + CREATE_DIALOG + "表单「登录名」填写 1-50 个字符后保存";
    }

    public static String loginNameExists(String username) {
        return "登录名 " + username + " 已存在，请更换；可在" + USER_ENTRY + "搜索「" + username
                + "」确认归属后，在" + CREATE_DIALOG + "表单改成带后缀的名字（如 " + username + "-2）";
    }

    public static String statusInvalid(int status) {
        return "状态仅支持 1=启用 / 0=停用（收到 " + status + "）；请在" + USER_ENTRY
                + "的行编辑表单把「状态」开关改为启用或停用后保存";
    }

    public static String oldPasswordMismatch() {
        return "原密码不正确；请在" + PROFILE_ENTRY + "的" + PASSWORD_TAB + "重新输入「原密码」后点「确认修改」，"
                + "忘记当前密码可在" + USER_ENTRY + "由管理员用" + RESET_DIALOG + "设置新密码（重置后旧密码立即失效）";
    }

    public static String newPasswordSameAsOld() {
        return "新密码不能与原密码相同；请在" + PROFILE_ENTRY + "的" + PASSWORD_TAB
                + "换一个满足 " + PASSWORD_RULE + " 的密码后点「确认修改」";
    }

    public static String nicknameTooLong(int submittedLength) {
        return "昵称不能超过 100 字符（本次提交长度= " + submittedLength + "）；请在" + PROFILE_ENTRY + "的"
                + INFO_TAB + "「昵称」缩短内容后点" + SAVE_BUTTON;
    }

    public static String avatarUrlTooLong(int submittedLength) {
        return "头像地址不能超过 200 字符（本次提交长度= " + submittedLength + "）；请在" + PROFILE_ENTRY
                + "的" + INFO_TAB + "「头像 URL」换成更短的图片链接，或清空该栏后点" + SAVE_BUTTON;
    }

    public static String deleteSelfForbidden(long userId) {
        return "不能删除当前登录账号，请先移交管理员职责（账号 id= " + userId + "）；请到" + USER_ENTRY
                + "先把该账号的角色改为其他角色，再由另一名管理员在" + USER_ENTRY + "删除该行";
    }

    public static String retryRowsEmpty() {
        return "请选择需要重试的失败项（行号不能为空）；请在" + USER_ENTRY + "的" + IMPORT_DIALOG
                + "弹窗结果表中勾选失败行，再点" + RETRY_BUTTON + "重新提交";
    }

    public static String userNotFound(long userId) {
        return "用户不存在或已被删除（用户 id= " + userId + "）；请到" + USER_ENTRY
                + "刷新列表确认该行，列表没有说明账号已被删除，需要在" + CREATE_DIALOG + "重新新建用户";
    }

    public static String accountLocked(long remainMinutes) {
        return "账号已锁定，请 " + remainMinutes + " 分钟后再试；锁定时长由系统参数 " + LOCK_MINUTES_KEY
                + " 控制（可在「系统配置」页修改），也可以不等窗口过去：请管理员在" + USER_ENTRY
                + "该行点「解锁」，解锁后该账号可正常登录";
    }

    public static String accountDisabled(String username) {
        return "该账号已停用（登录名= " + username + "）；请到" + USER_ENTRY + "搜索「" + username
                + "」，在行编辑表单把「状态」改为启用后重新登录";
    }
}
