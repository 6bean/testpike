package com.sixbean.testpike.core.apitest.domain.component;

/**
 * Single-source refusal wording for the API run door, the executor result
 * recording path and the response snapshot compare flow, under the smart
 * guidance contract: every sentence echoes the offending submitted value,
 * quotes the on-screen repair entry in 「」, and keeps the legacy
 * capability clause contiguous so existing service and compare assertions
 * hold.
 *
 * <p>Quoted literals must match the shipped frontend wording of the API
 * test page (run set launcher, run history entry, response compare
 * button); executor-side sentences name the executor callback contract
 * first and the human verification route second.
 */
public final class ApiRunRefusals {

    /** Page that hosts the interface tree, the run door and the compare dialog. */
    public static final String API_TEST_ENTRY = "「接口测试」页";

    /** On-screen entry that lists run records of a collection. */
    public static final String RUN_HISTORY_ENTRY = "「接口集运行记录」";

    /** On-screen button that opens the response compare dialog. */
    public static final String COMPARE_BUTTON = "「比对响应」";

    /** On-screen button that launches a whole-collection run. */
    public static final String RUN_SET_BUTTON = "「运行整个接口集」";

    /** Cap of the echoed raw value so huge payloads never flood the banner. */
    private static final int ECHO_MAX = 200;

    private ApiRunRefusals() {
    }

    public static String runRequestMissing(Long requestId) {
        return "运行必须指定 API 请求（本次提交 requestId=" + (requestId == null ? "空" : requestId)
                + "）；请在" + API_TEST_ENTRY + "先选中一个 API 请求再发起运行，批量场景可用"
                + RUN_SET_BUTTON;
    }

    public static String runRecordMissing(long runId) {
        return "运行记录不存在或已清理（运行 id= " + runId + "）；请在" + API_TEST_ENTRY + "的"
                + RUN_HISTORY_ENTRY + "刷新列表确认；记录被留存清理回收后，需重新发起一次运行才会再次出现";
    }

    public static String statusUnsupported(int status) {
        return "运行状态仅支持 0=待执行 / 1=通过 / 2=断言失败 / 3=执行错误 / 4=诚实跳过（收到 "
                + status + "）；请执行器侧按上述取值之一回传，人工核对请在" + API_TEST_ENTRY + "的"
                + RUN_HISTORY_ENTRY + "查看该运行的状态与错误信息";
    }

    public static String artifactRefTooLong(int maxChars, int submittedLength) {
        return "工件引用最长 " + maxChars + " 字（本次提交长度= " + submittedLength
                + "）；请执行器回传不超过 " + maxChars + " 字的产物引用，人工核对产物请在"
                + RUN_HISTORY_ENTRY + "的运行详情查看日志与快照";
    }

    public static String noDispatchSnapshot(long runId) {
        return "该运行未派发执行或派发单已清理，无响应快照可比对（运行 id= " + runId
                + "）；请先在" + API_TEST_ENTRY + "重新发起该运行，产生派发单后再勾选记录点"
                + COMPARE_BUTTON;
    }

    public static String missingResponseRecord(long runId) {
        return "该运行的步骤快照缺少响应记录，无法比对（运行 id= " + runId + "）；请到"
                + API_TEST_ENTRY + "重新发起该运行生成完整快照后再点" + COMPARE_BUTTON
                + "；反复出现时请在" + RUN_HISTORY_ENTRY + "查看该运行步骤 1 的执行错误信息";
    }

    public static String invalidSnapshotBody(String reason) {
        return "响应快照结构非法（缺 body 字段），无法比对: " + brief(reason)
                + "；请重新发起该运行覆盖快照后再勾选记录点" + COMPARE_BUTTON
                + "；如仍失败请在" + API_TEST_ENTRY + "检查该请求的响应格式";
    }

    public static String requestMissing(long requestId) {
        return "API 请求不存在或已删除（请求 id= " + requestId + "）；请在" + API_TEST_ENTRY
                + "刷新接口树确认该请求；请求已删除时需重新新建请求后再发起运行";
    }

    public static String assertDetailNotJson(String raw) {
        return "断言明细不是合法 JSON（执行器回传数组原文），当前值「" + brief(raw)
                + "」；请执行器按形如 [{...}] 的数组 JSON 回传，人工核对请在" + RUN_HISTORY_ENTRY
                + "查看该运行的错误信息后重新发起";
    }

    public static String assertDetailNotArray(String raw) {
        return "断言明细必须是数组（形如 [{...}]，本次提交以「" + brief(raw)
                + "」开头）；请执行器回传数组 JSON，人工可在" + API_TEST_ENTRY + "的"
                + RUN_HISTORY_ENTRY + "重新发起该请求生成明细";
    }

    /** Truncate an echoed raw value so the banner stays readable. */
    private static String brief(String value) {
        if (value == null || value.isBlank()) {
            return "空";
        }
        String trimmed = value.trim();
        return trimmed.length() <= ECHO_MAX ? trimmed : trimmed.substring(0, ECHO_MAX) + "…";
    }
}
