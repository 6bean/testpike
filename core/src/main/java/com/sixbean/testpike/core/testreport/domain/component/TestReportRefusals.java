package com.sixbean.testpike.core.testreport.domain.component;

/**
 * Single-source refusal wording for the test-report generate, detail and
 * export doors under the R63 smart-guidance contract: every sentence echoes
 * the offending submitted value, quotes the on-screen repair entry in 「」,
 * and keeps the legacy capability clause contiguous so the existing
 * test-report service assertions hold.
 *
 * <p>Quoted literals must match the shipped frontend wording (the summary
 * report page generates reports through a dialog with a range-type radio, a
 * plan picker and a date-range control, and exports through the row buttons).
 * Keep them in sync when the screens change.
 */
public final class TestReportRefusals {

    /** Repair entry quoted for report generate, detail and export refusals. */
    public static final String REPORT_ENTRY = "「总结报告」页";

    /** Repair entry quoted when the selected plan has to be found or replaced. */
    public static final String PLAN_ENTRY = "「执行计划」页";

    /** On-screen label of the range-type radio inside the generate dialog. */
    public static final String RANGE_TYPE_CONTROL = "「范围类型」";

    /** On-screen title of the generate dialog. */
    public static final String GENERATE_DIALOG = "「生成报告」";

    /** On-screen labels of the two row export buttons. */
    public static final String EXPORT_XLSX_BUTTON = "「导出 Excel」";

    public static final String EXPORT_PDF_BUTTON = "「导出 PDF」";

    private TestReportRefusals() {
    }

    public static String rangeTypeInvalid(Object received) {
        return "报告范围类型不合法（1=计划 2=时间范围），收到 " + received + "；请在" + REPORT_ENTRY
                + "的" + GENERATE_DIALOG + "把" + RANGE_TYPE_CONTROL + "改为 1=计划 或 2=时间范围后重新提交";
    }

    public static String planRequired() {
        return "按计划生成必须选择计划；请在" + REPORT_ENTRY + "的" + GENERATE_DIALOG
                + "把" + RANGE_TYPE_CONTROL + "选为 1=计划，并在「计划」下拉中选中一行（列表来自" + PLAN_ENTRY + "）";
    }

    public static String planNotFound(long planId) {
        return "所选计划不存在（计划 id= " + planId + "）；请到" + PLAN_ENTRY
                + "确认该计划是否已被删除，再回到" + REPORT_ENTRY + "的" + GENERATE_DIALOG + "重新选择计划";
    }

    public static String planOtherProject(long planId, long projectId) {
        return "所选计划不属于当前项目（计划 id= " + planId + "，当前项目 id= " + projectId
                + "）；请在顶栏项目切换到该计划所属项目，或在" + REPORT_ENTRY + "的" + GENERATE_DIALOG
                + "改选本项目的计划";
    }

    public static String timeRangeRequired(String startTime, String endTime) {
        return "按时间范围生成必须提供起止日期（收到开始日期=" + describe(startTime) + "、结束日期="
                + describe(endTime) + "）；请在" + REPORT_ENTRY + "的" + GENERATE_DIALOG
                + "「时间范围」中同时选择开始日期与结束日期后提交";
    }

    public static String serializeFailed(String title, String cause) {
        return "报告序列化失败（报告标题= " + title + "，原因= " + cause + "）；请缩短报告标题或缩小生成范围后重新点击"
                + GENERATE_DIALOG + "的提交，持续失败请到" + REPORT_ENTRY + "改用已有报告导出";
    }

    public static String reportNotFound(long reportId) {
        return "报告不存在（报告 id= " + reportId + "）；请到" + REPORT_ENTRY
                + "刷新列表确认该行，列表里没有说明报告已被删除或超出留存期";
    }

    public static String exportFormatUnsupported(String format) {
        return "导出格式不支持（仅支持 xlsx/pdf），收到 " + format + "；请在" + REPORT_ENTRY
                + "的行内改用" + EXPORT_XLSX_BUTTON + "或" + EXPORT_PDF_BUTTON + "发起导出";
    }

    public static String dataParseFailed(String cause) {
        return "报告数据解析失败（原因= " + cause + "）；请到" + REPORT_ENTRY
                + "重新点击「生成报告」重建该行，重建仍失败说明报告内容已损坏，请把报告 id 反馈管理员排查";
    }

    public static String excelExportFailed(String cause) {
        return "Excel 导出失败（原因= " + cause + "）；请先在" + REPORT_ENTRY + "改用" + EXPORT_PDF_BUTTON
                + "拿到同一份报告，稍后重试" + EXPORT_XLSX_BUTTON + "；持续失败请把报告 id 反馈管理员";
    }

    public static String pdfExportFailed(String cause) {
        return "PDF 导出失败（原因= " + cause + "）；请先在" + REPORT_ENTRY + "改用" + EXPORT_XLSX_BUTTON
                + "拿到同一份报告，稍后重试" + EXPORT_PDF_BUTTON + "；持续失败请把报告 id 反馈管理员";
    }

    private static String describe(String value) {
        if (value == null) {
            return "未填写";
        }
        return value.isBlank() ? "空白" : value;
    }
}
