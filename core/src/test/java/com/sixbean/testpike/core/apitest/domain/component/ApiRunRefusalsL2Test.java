package com.sixbean.testpike.core.apitest.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smart-guidance contract for the API run door, the executor result
 * recording path and the response snapshot compare flow: every refusal
 * echoes the offending submitted value, names the on-screen repair entry,
 * and keeps the legacy capability clause contiguous so the existing
 * api-run service and compare assertions stay green.
 */
class ApiRunRefusalsL2Test {

    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    private static void assertNamesApiTestEntry(String message) {
        assertTrue(message.contains("接口测试"),
                "message must name the on-screen repair entry. actual: " + message);
    }

    @Test
    void runRequestMissingEchoesSubmittedIdAndNamesEntry() {
        String msg = ApiRunRefusals.runRequestMissing(null);
        assertKeepsLegacy(msg, "运行必须指定 API 请求");
        assertTrue(msg.contains("本次提交"), "must echo the submitted id. actual: " + msg);
        assertNamesApiTestEntry(msg);
        assertTrue(ApiRunRefusals.runRequestMissing(0L).contains("0"),
                "must echo the zero id");
    }

    @Test
    void runRecordMissingEchoesRunIdAndNamesHistory() {
        String msg = ApiRunRefusals.runRecordMissing(77);
        assertKeepsLegacy(msg, "运行记录不存在或已清理");
        assertTrue(msg.contains("77"), "must echo the run id. actual: " + msg);
        assertTrue(msg.contains("接口集运行记录"), "must name the run history entry. actual: " + msg);
    }

    @Test
    void statusUnsupportedKeepsEnumAndEchoesValue() {
        String msg = ApiRunRefusals.statusUnsupported(9);
        assertKeepsLegacy(msg, "运行状态仅支持 0=待执行 / 1=通过 / 2=断言失败 / 3=执行错误 / 4=诚实跳过");
        assertTrue(msg.contains("收到 9"), "must echo the received status. actual: " + msg);
        assertNamesApiTestEntry(msg);
    }

    @Test
    void artifactRefTooLongEchoesLimitAndSubmittedLength() {
        String msg = ApiRunRefusals.artifactRefTooLong(200, 260);
        assertKeepsLegacy(msg, "工件引用最长 200 字");
        assertTrue(msg.contains("260"), "must echo the submitted length. actual: " + msg);
    }

    @Test
    void noDispatchSnapshotEchoesRunIdAndNamesCompareButton() {
        String msg = ApiRunRefusals.noDispatchSnapshot(81);
        assertKeepsLegacy(msg, "该运行未派发执行或派发单已清理，无响应快照可比对");
        assertTrue(msg.contains("81"), "must echo the run id. actual: " + msg);
        assertTrue(msg.contains("比对响应"), "must name the compare button. actual: " + msg);
    }

    @Test
    void missingResponseRecordEchoesRunIdAndNamesRepairRoute() {
        String msg = ApiRunRefusals.missingResponseRecord(82);
        assertKeepsLegacy(msg, "该运行的步骤快照缺少响应记录，无法比对");
        assertTrue(msg.contains("82"), "must echo the run id. actual: " + msg);
        assertNamesApiTestEntry(msg);
    }

    @Test
    void invalidSnapshotBodyKeepsReasonAndNamesCompareButton() {
        String msg = ApiRunRefusals.invalidSnapshotBody("Unexpected end");
        assertKeepsLegacy(msg, "响应快照结构非法（缺 body 字段），无法比对");
        assertTrue(msg.contains("Unexpected end"), "must keep the parse reason. actual: " + msg);
        assertTrue(msg.contains("比对响应"), "must name the compare button. actual: " + msg);
    }

    @Test
    void requestMissingKeepsWordEchoesIdAndNamesEntry() {
        String msg = ApiRunRefusals.requestMissing(5);
        assertTrue(msg.contains("请求"), "legacy word 请求 must stay. actual: " + msg);
        assertTrue(msg.contains("5"), "must echo the request id. actual: " + msg);
        assertNamesApiTestEntry(msg);
    }

    @Test
    void assertDetailNotJsonEchoesRawValue() {
        String msg = ApiRunRefusals.assertDetailNotJson("oops{");
        assertKeepsLegacy(msg, "断言明细不是合法 JSON（执行器回传数组原文）");
        assertTrue(msg.contains("oops{"), "must echo the submitted raw. actual: " + msg);
    }

    @Test
    void assertDetailNotArrayKeepsShapeHint() {
        String msg = ApiRunRefusals.assertDetailNotArray("{\"passed\":true}");
        assertKeepsLegacy(msg, "断言明细必须是数组");
        assertTrue(msg.contains("[{...}]"), "must restate the array shape. actual: " + msg);
        assertNamesApiTestEntry(msg);
    }
}
