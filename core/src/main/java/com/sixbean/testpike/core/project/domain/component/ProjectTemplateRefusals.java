package com.sixbean.testpike.core.project.domain.component;

/**
 * Single-source refusal wording for the industry template doors under the
 * R63 smart-guidance contract: create/update field limits, template lookup,
 * the content four-piece validation and persistence failures. Templates are
 * picked on the project creation dialog of the project management screen,
 * so each sentence echoes the offending value, quotes the on-screen entry or
 * the payload field in 「」, and names the concrete next step.
 *
 * <p>The quoted literals must match the shipped frontend wording: the
 * project management page, its new-project dialog and the industry-template
 * selector. Keep them in sync when the screens change.
 */
public final class ProjectTemplateRefusals {

    /** Repair entry quoted for template lookups and selection refusals. */
    public static final String PROJECTS_ENTRY = "「项目管理」页";

    /** On-screen label of the page-level create action. */
    public static final String CREATE_ACTION = "「新建项目」";

    /** On-screen label of the template selector inside the dialog. */
    public static final String TEMPLATE_FIELD = "「行业模板」";

    private ProjectTemplateRefusals() {
    }

    public static String nameInvalid(String submitted) {
        int len = submitted == null ? 0 : submitted.trim().length();
        return "提交的模板名称长度是 " + len + " 字——「模板名称」必填且不超过 100 字"
                + "（示例：零售订单中台行业模板）；请在编辑表单里修正后重新提交";
    }

    public static String industryTooLong(int length) {
        return "提交的行业标识长度是 " + length + " 字，超过「行业标识」上限 50 字"
                + "（示例：零售、金融）；请缩短后重新提交";
    }

    public static String templateMissing(long templateId) {
        return "找不到 id= " + templateId + " 的行业模板，可能已被删除；请到" + PROJECTS_ENTRY + CREATE_ACTION
                + "表单的" + TEMPLATE_FIELD + "下拉里重新选择在册模板";
    }

    public static String contentMissing() {
        return "提交的模板内容为空——「模板内容」四件套（SKILL引用/预置标签/示例环境/角色建议）"
                + "结构必须齐全，空数组可以接受；请按四件套补全后重新提交";
    }

    public static String skillIdNull(int index) {
        return "「skillIds」第 " + index + " 项为空——SKILL 引用必须是有效编号（示例 [12, 37]）；"
                + "请删除空项后重新提交";
    }

    public static String skillIdDuplicated(long skillId) {
        return "SKILL id= " + skillId + " 在「skillIds」里出现多次，同一 SKILL 每个模板只能引用一次；"
                + "请去重后重新提交";
    }

    public static String tagNameBlank(int index) {
        return "预置标签第 " + index + " 项名称为空——「预置标签」每项都要有名称（示例：回归冒烟）；"
                + "请补全名称或删除该空行后重新提交";
    }

    public static String tagNameDuplicated(String tagName) {
        return "预置标签「" + tagName + "」重复——同一模板内标签名称必须唯一；"
                + "请在「预置标签」列表里删除多余一行或改名后重新提交";
    }

    public static String envNameBlank(int index) {
        return "示例环境第 " + index + " 项名称为空——「示例环境」每项都要有名称（示例：测试环境）；"
                + "请补全名称后重新提交";
    }

    public static String envNameDuplicated(String envName) {
        return "示例环境「" + envName + "」重复——同一模板内环境名称必须唯一；"
                + "请在「示例环境」列表里保留一条或改名后重新提交";
    }

    public static String contentParseFailed(long templateId) {
        return "id= " + templateId + " 的行业模板内容解析失败，库内 JSON 已损坏，该模板暂不可用于建项目；"
                + "请联系管理员按" + TEMPLATE_FIELD + "创建接口重建此模板（原 id 的行需先清理）";
    }

    public static String contentSerializeFailed() {
        return "提交的「模板内容」无法序列化保存，疑似名称里带了异常字符；"
                + "请去掉标签与环境名称中的特殊字符后重新保存，仍失败请把表单内容导出后反馈管理员";
    }
}
