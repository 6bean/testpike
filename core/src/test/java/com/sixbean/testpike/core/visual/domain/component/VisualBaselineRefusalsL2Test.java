package com.sixbean.testpike.core.visual.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smart-guidance contract for the visual baseline candidate record,
 * confirm, reject, view and tolerance doors: every refusal echoes the
 * offending submitted value, names the on-screen repair entry inside the
 * case detail page, and keeps the legacy capability clause contiguous so
 * the existing visual baseline service assertions stay green.
 */
class VisualBaselineRefusalsL2Test {

    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    @Test
    void projectMissingEchoesSubmittedFieldAndNamesEntry() {
        String msg = VisualBaselineRefusals.projectMissing();
        assertKeepsLegacy(msg, "项目不能为空");
        assertTrue(msg.contains("本次提交"), "must echo the submitted field. actual: " + msg);
        assertTrue(msg.contains("用例管理"), "must name the entry. actual: " + msg);
    }

    @Test
    void stepKeyBlankNamesTheStepSourceAndRepair() {
        String msg = VisualBaselineRefusals.stepKeyBlank();
        assertKeepsLegacy(msg, "基线稳定键（stepKey）不能为空");
        assertTrue(msg.contains("用例详情"), "must name the case detail page. actual: " + msg);
    }

    @Test
    void stepKeyTooLongEchoesLimitAndSubmittedLength() {
        String msg = VisualBaselineRefusals.stepKeyTooLong(64, 80);
        assertKeepsLegacy(msg, "基线稳定键超长（上限 64 字符）");
        assertTrue(msg.contains("80"), "must echo the submitted length. actual: " + msg);
    }

    @Test
    void sourceKeyBlankKeepsUploadClauseAndGivesRepair() {
        String msg = VisualBaselineRefusals.sourceKeyBlank();
        assertKeepsLegacy(msg, "来源截图对象键不能为空（执行采集产物先行上传后登记）");
        assertTrue(msg.contains("重新执行"), "must give the repair action. actual: " + msg);
    }

    @Test
    void projectNotFoundEchoesProjectIdAndNamesEntry() {
        String msg = VisualBaselineRefusals.projectNotFound(9);
        assertKeepsLegacy(msg, "基线挂接的项目不存在");
        assertTrue(msg.contains("9"), "must echo the project id. actual: " + msg);
        assertTrue(msg.contains("用例管理"), "must name the entry. actual: " + msg);
    }

    @Test
    void confirmNotPendingEchoesStatusAndNamesButton() {
        String msg = VisualBaselineRefusals.confirmNotPending("已确认基线");
        assertKeepsLegacy(msg, "仅待确认候选可确认为基线");
        assertTrue(msg.contains("已确认基线"), "must echo the current status. actual: " + msg);
        assertTrue(msg.contains("确认为基线"), "must name the confirm button. actual: " + msg);
    }

    @Test
    void sourceBytesEmptyKeepsScreenshotWordAndEchoesKey() {
        String msg = VisualBaselineRefusals.sourceBytesEmpty("case/1/a.png");
        assertKeepsLegacy(msg, "拒绝确认空基线");
        assertTrue(msg.contains("截图"), "legacy word 截图 must stay. actual: " + msg);
        assertTrue(msg.contains("case/1/a.png"), "must echo the artifact key. actual: " + msg);
    }

    @Test
    void rejectNotPendingEchoesStatusAndNamesButton() {
        String msg = VisualBaselineRefusals.rejectNotPending("已确认基线");
        assertKeepsLegacy(msg, "仅待确认候选可拒绝");
        assertTrue(msg.contains("已确认基线"), "must echo the current status. actual: " + msg);
        assertTrue(msg.contains("拒绝"), "must name the reject button. actual: " + msg);
    }

    @Test
    void baselineMissingEchoesIdAndNamesPanel() {
        String msg = VisualBaselineRefusals.baselineMissing(3);
        assertKeepsLegacy(msg, "基线记录不存在");
        assertTrue(msg.contains("3"), "must echo the row id. actual: " + msg);
        assertTrue(msg.contains("视觉基线"), "must name the panel. actual: " + msg);
    }

    @Test
    void noViewableShotEchoesStatusAndId() {
        String msg = VisualBaselineRefusals.noViewableShot("已拒绝候选", 8);
        assertKeepsLegacy(msg, "没有可查看的截图对象");
        assertTrue(msg.contains("已拒绝候选"), "must echo the status. actual: " + msg);
        assertTrue(msg.contains("8"), "must echo the row id. actual: " + msg);
    }

    @Test
    void toleranceInvalidKeepsRangeAndEchoesValue() {
        String msg = VisualBaselineRefusals.toleranceInvalid(1.5);
        assertKeepsLegacy(msg, "基线容差须在 0~1 之间");
        assertTrue(msg.contains("容差"), "legacy word 容差 must stay. actual: " + msg);
        assertTrue(msg.contains("1.5"), "must echo the submitted tolerance. actual: " + msg);
    }
}
