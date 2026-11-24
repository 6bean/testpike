package com.sixbean.testpike.core.visual.domain.component;

/**
 * Single-source refusal wording for the visual baseline candidate record,
 * confirm, reject, view and tolerance doors, under the smart guidance
 * contract: every sentence echoes the offending submitted value, quotes
 * the on-screen repair entry in 「」, and keeps the legacy capability
 * clause contiguous so existing visual baseline service assertions hold.
 *
 * <p>Candidates are written by the executor during visual assertion
 * steps, so sentences pair the executor-side repair with the human route
 * inside the case detail page visual baseline panel. Quoted literals must
 * match the shipped frontend wording (case management page, case detail
 * page, visual baseline panel, confirm and reject buttons).
 */
public final class VisualBaselineRefusals {

    /** Page that owns the case list and the project affiliation. */
    public static final String CASE_MGMT_ENTRY = "「用例管理」页";

    /** Page hosting the visual baseline panel. */
    public static final String CASE_DETAIL_ENTRY = "「用例详情」页";

    /** On-screen panel title inside the case detail page. */
    public static final String BASELINE_PANEL = "「视觉基线」面板";

    /** On-screen button that promotes a pending candidate. */
    public static final String CONFIRM_BUTTON = "「确认为基线」";

    /** On-screen button that drops a pending candidate. */
    public static final String REJECT_BUTTON = "「拒绝」";

    private VisualBaselineRefusals() {
    }

    public static String projectMissing() {
        return "项目不能为空（本次提交 project_id=空）；候选由执行器在视觉断言步骤登记，请在"
                + CASE_MGMT_ENTRY + "打开该用例确认归属项目后重新执行";
    }

    public static String stepKeyBlank() {
        return "基线稳定键（stepKey）不能为空（本次提交 stepKey=空）；步骤键由用例与步骤自动派生，请在"
                + CASE_DETAIL_ENTRY + "确认该用例步骤完整后重新执行";
    }

    public static String stepKeyTooLong(int maxChars, int submittedLength) {
        return "基线稳定键超长（上限 " + maxChars + " 字符），本次提交长度= " + submittedLength
                + "；请缩短对应用例的步骤命名后重新执行，人工核对请在" + BASELINE_PANEL + "查看候选行";
    }

    public static String sourceKeyBlank() {
        return "来源截图对象键不能为空（执行采集产物先行上传后登记）（本次提交截图对象键=空）；"
                + "请先为该用例步骤开启截图采集产物上传，再重新执行一次以生成候选";
    }

    public static String projectNotFound(long projectId) {
        return "基线挂接的项目不存在（project_id= " + projectId + "）；项目可能已被删除，请在"
                + CASE_MGMT_ENTRY + "把该用例归入现有项目后重新执行";
    }

    public static String confirmNotPending(String statusLabel) {
        return "仅待确认候选可确认为基线（当前状态：" + statusLabel + "）；" + CONFIRM_BUTTON
                + "只作用于" + BASELINE_PANEL + "中状态为待确认的行；该行已是基线或历史版本时请直接查看截图，"
                + "需要新基线请重新执行该步骤";
    }

    public static String sourceBytesEmpty(String artifactKey) {
        return "来源截图字节读取失败或为空，拒绝确认空基线（截图对象 key= " + artifactKey
                + "）；产物可能已被留存清理，请重新执行该用例步骤生成新的候选截图后再点" + CONFIRM_BUTTON;
    }

    public static String rejectNotPending(String statusLabel) {
        return "仅待确认候选可拒绝（当前状态：" + statusLabel + "）；" + REJECT_BUTTON
                + "只作用于" + BASELINE_PANEL + "中状态为待确认的行；已确认的旧基线会随新基线确认自动让位为历史版本，无需手工拒绝";
    }

    public static String baselineMissing(long id) {
        return "基线记录不存在（id= " + id + "）；请在" + CASE_DETAIL_ENTRY + "的" + BASELINE_PANEL
                + "刷新列表；该行可能已被拒绝删除或已被新版本替换";
    }

    public static String noViewableShot(String statusLabel, long id) {
        return "该行没有可查看的截图对象（状态：" + statusLabel + "，id= " + id + "）；请重新执行该用例步骤"
                + "生成候选截图，或在" + BASELINE_PANEL + "改选有截图的行查看";
    }

    public static String toleranceInvalid(Double tolerance) {
        return "基线容差须在 0~1 之间（差异像素占比；当前 " + tolerance + "）；请执行器回传 0~1 的比例值，"
                + "人工调整请在" + CASE_MGMT_ENTRY + "编辑该用例视觉断言步骤的容差后重新执行";
    }
}
