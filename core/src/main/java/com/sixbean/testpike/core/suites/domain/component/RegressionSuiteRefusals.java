package com.sixbean.testpike.core.suites.domain.component;

/**
 * Single-source refusal wording for the regression suite doors under the R63
 * smart-guidance contract: listing, one-click generation preconditions and
 * the create/update validation. Every sentence echoes the offending submitted
 * value, quotes the on-screen repair entry in 「」 and keeps the legacy
 * capability clause contiguous so existing assertions hold.
 *
 * <p>Quoted literals must match the shipped frontend wording (the
 * regression-suite tab inside the case-management page, the case list,
 * project members page). Keep them in sync when the screens change.
 */
public final class RegressionSuiteRefusals {

    /** Repair entry quoted for suite rows and the edit panel. */
    public static final String SUITES_ENTRY = "「回归集」页";

    private RegressionSuiteRefusals() {
    }

    public static String suiteMissing(long id) {
        return "回归集不存在（id= " + id + "），可能已被删除；请回到" + SUITES_ENTRY + "从列表重新选择";
    }

    public static String suiteDisabled(long id) {
        return "回归集已停用，请先启用后再一键生成（id= " + id + "）；请在" + SUITES_ENTRY
                + "该行点「编辑」重新启用";
    }

    public static String noCases(long id) {
        return "回归集内无用例，请先添加用例再一键生成（id= " + id + "）；请在" + SUITES_ENTRY
                + "点「编辑」→「用例圈选」勾选至少 1 条用例";
    }

    public static String noEnv(long id) {
        return "回归集未配置环境，请先选择环境再一键生成（id= " + id + "）；请在" + SUITES_ENTRY
                + "点「编辑」，在「执行环境」中勾选本项目环境";
    }

    public static String noAssignee(long id) {
        return "回归集未配置默认执行人，请先配置后再一键生成（id= " + id + "）；请在" + SUITES_ENTRY
                + "点「编辑」，在「默认执行人」中勾选本项目成员";
    }

    public static String allCasesArchived(int skipped) {
        return "回归集内 " + skipped + " 条用例均已归档/删除，无法生成计划；请到「用例管理」列表核对这些用例的状态，"
                + "并在" + SUITES_ENTRY + "点「编辑」→「用例圈选」更新用例清单后重试";
    }

    public static String nameBlank() {
        return "回归集名称不能为空；请在" + SUITES_ENTRY + "的「名称」输入框填写（不超过 100 个字符）后保存";
    }

    public static String nameTooLong(int length) {
        return "回归集名称长度不能超过 100 个字符，本次提交 " + length + " 个字符；"
                + "请精简「名称」后再保存（" + SUITES_ENTRY + "编辑面板）";
    }

    public static String casesEmpty() {
        return "请选择回归集用例（至少 1 条）；请在" + SUITES_ENTRY + "新建/编辑面板点「用例圈选」"
                + "勾选本项目的用例后再保存";
    }

    public static String envEmpty() {
        return "请选择回归集执行环境；请在" + SUITES_ENTRY + "新建/编辑面板的「执行环境」下拉中"
                + "至少选择 1 个本项目环境后再保存";
    }

    public static String foreignCases(int mismatched, int submitted) {
        return "存在不属于该项目的用例，回归集保存失败：提交的 " + submitted + " 条中有 " + mismatched
                + " 条不在本项目；请到「用例管理」页确认用例归属项目，或改选本项目的用例";
    }

    public static String assigneeNotMember(long userId) {
        return "默认执行人（用户 ID= " + userId + "）不是本项目成员；请到「项目成员」页添加该成员，"
                + "或在" + SUITES_ENTRY + "「编辑」→「默认执行人」改选已有成员";
    }

    public static String serializeFailed() {
        return "参数序列化失败，本次保存已回滚、未写入任何数据；请重试，若持续报错请检查"
                + SUITES_ENTRY + "表单中的用例/环境/执行人清单是否为有效选择后重新保存";
    }
}
