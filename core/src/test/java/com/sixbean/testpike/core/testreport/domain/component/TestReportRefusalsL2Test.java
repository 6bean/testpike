package com.sixbean.testpike.core.testreport.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 contract for the test-report generate/detail/export doors: every refusal
 * echoes the offending submitted value, names the on-screen repair entry in
 * 「」, and keeps the legacy capability clause contiguous so the existing
 * test-report service assertions stay green.
 */
class TestReportRefusalsL2Test {

    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    private static void assertNamesEntry(String message) {
        assertTrue(message.contains("「总结报告」"),
                "message must name the on-screen repair entry. actual: " + message);
    }

    @Test
    void rangeTypeInvalidEchoesReceivedValueAndNamesRadio() {
        String msg = TestReportRefusals.rangeTypeInvalid(7);
        assertKeepsLegacy(msg, "报告范围类型不合法（1=计划 2=时间范围）");
        assertTrue(msg.contains("收到 7"), "must echo the received rangeType. actual: " + msg);
        assertTrue(msg.contains("范围类型"), "must name the radio control. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void planRequiredNamesPlanPickerInGenerateDialog() {
        String msg = TestReportRefusals.planRequired();
        assertKeepsLegacy(msg, "按计划生成必须选择计划");
        assertTrue(msg.contains("生成报告"), "must name the generate dialog. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void planNotFoundEchoesPlanIdAndPointsToPlanPage() {
        String msg = TestReportRefusals.planNotFound(3312L);
        assertKeepsLegacy(msg, "所选计划不存在");
        assertTrue(msg.contains("3312"), "must echo the submitted plan id. actual: " + msg);
        assertTrue(msg.contains("「执行计划」"), "must name the plan page. actual: " + msg);
    }

    @Test
    void planOtherProjectEchoesBothIdsAndNamesHeaderSwitch() {
        String msg = TestReportRefusals.planOtherProject(3312L, 88L);
        assertKeepsLegacy(msg, "所选计划不属于当前项目");
        assertTrue(msg.contains("3312") && msg.contains("88"),
                "must echo plan id and project id. actual: " + msg);
        assertTrue(msg.contains("顶栏"), "must name the project switch. actual: " + msg);
    }

    @Test
    void timeRangeRequiredEchoesMissingFieldNames() {
        String msg = TestReportRefusals.timeRangeRequired(null, "   ");
        assertKeepsLegacy(msg, "按时间范围生成必须提供起止日期");
        assertTrue(msg.contains("开始日期"), "must point out the missing start field. actual: " + msg);
        assertTrue(msg.contains("结束日期"), "must point out the missing end field. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void serializeFailedEchoesTitleAndBrokerReason() {
        String msg = TestReportRefusals.serializeFailed("九月第一周周报", "No serializer found");
        assertKeepsLegacy(msg, "报告序列化失败");
        assertTrue(msg.contains("九月第一周周报"), "must echo the report title. actual: " + msg);
        assertTrue(msg.contains("No serializer found"), "must carry the cause. actual: " + msg);
    }

    @Test
    void reportNotFoundEchoesReportIdAndSuggestsReload() {
        String msg = TestReportRefusals.reportNotFound(904L);
        assertKeepsLegacy(msg, "报告不存在");
        assertTrue(msg.contains("904"), "must echo the report id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void exportFormatUnsupportedEchoesFormatAndNamesButtons() {
        String msg = TestReportRefusals.exportFormatUnsupported("CSV");
        assertKeepsLegacy(msg, "导出格式不支持（仅支持 xlsx/pdf）");
        assertTrue(msg.contains("CSV"), "must echo the received format. actual: " + msg);
        assertTrue(msg.contains("导出 Excel"), "must name the xlsx button. actual: " + msg);
        assertTrue(msg.contains("导出 PDF"), "must name the pdf button. actual: " + msg);
    }

    @Test
    void dataParseFailedCarriesCauseAndRepairEntry() {
        String msg = TestReportRefusals.dataParseFailed("Unrecognized token 'op'");
        assertKeepsLegacy(msg, "报告数据解析失败");
        assertTrue(msg.contains("Unrecognized token 'op'"), "must carry the cause. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void excelExportFailedCarriesCauseAndPdfFallback() {
        String msg = TestReportRefusals.excelExportFailed("Fontconfig head is null");
        assertKeepsLegacy(msg, "Excel 导出失败");
        assertTrue(msg.contains("Fontconfig head is null"), "must carry the cause. actual: " + msg);
        assertTrue(msg.contains("导出 PDF"), "must offer the pdf fallback. actual: " + msg);
    }

    @Test
    void pdfExportFailedCarriesCauseAndXlsxFallback() {
        String msg = TestReportRefusals.pdfExportFailed("no renderer available");
        assertKeepsLegacy(msg, "PDF 导出失败");
        assertTrue(msg.contains("no renderer available"), "must carry the cause. actual: " + msg);
        assertTrue(msg.contains("导出 Excel"), "must offer the xlsx fallback. actual: " + msg);
    }
}
