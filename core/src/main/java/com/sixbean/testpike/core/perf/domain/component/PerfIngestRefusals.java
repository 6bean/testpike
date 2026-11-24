package com.sixbean.testpike.core.perf.domain.component;

/**
 * Single-source refusal wording for the perf ingest doors under the R63
 * smart-guidance contract: metric-frame batch limits, per-frame field
 * validation, the intra-batch unique anchor, replay consistency against
 * stored rows, the event-stream row checks and the run-state gates. The
 * refusals surface on the perf run detail screen and back to the executor,
 * so every sentence echoes the offending value, quotes the protocol field
 * or the on-screen entry in 「」, and names the concrete next step.
 *
 * <p>The quoted literals must match the shipped frontend wording: the
 * load-test workbench page and the perf run ledger page. Keep them in sync
 * when the screens change.
 */
public final class PerfIngestRefusals {

    /** Repair entry quoted for stored-data lookups. */
    public static final String RUNS_ENTRY = "「压测运行台账」页";

    /** Repair entry quoted for starting a fresh run. */
    public static final String WORKBENCH_ENTRY = "「压测工作台」页";

    private PerfIngestRefusals() {
    }

    public static String batchTooLong(int submitted, int cap) {
        return "本批指标帧共 " + submitted + " 帧，超过单批上限 " + cap + " 帧（按 2 小时秒采样设计）；"
                + "请把每批拆分到不超过 " + cap + " 帧后再继续通过「上报指标帧」接口回传";
    }

    public static String secondOffsetIllegal(int rowNo, long value) {
        return "第 " + rowNo + " 帧的「secondOffset」= " + value + " 非法——秒偏移从 0 起计数（示例 0、1、2）；"
                + "请修正该帧取值后重新上报，已入库数据可在" + RUNS_ENTRY + "点开运行行核对";
    }

    public static String errorRateIllegal(int rowNo, double value) {
        return "第 " + rowNo + " 帧的「errorRate」= " + value + " 超出取值范围——该字段是 0 到 1 的小数"
                + "（0=零错误，0.05=错误率 5%）；请把百分比换算成小数后再重新上报";
    }

    public static String reqCountIllegal(int rowNo, long value) {
        return "第 " + rowNo + " 帧的「reqCount」= " + value + " 非法——请求数不能为负（示例 120）；"
                + "请检查执行器侧该秒的请求统计逻辑，修正后重新上报";
    }

    public static String failExceedsRequest(int rowNo, long failCount, long reqCount) {
        return "第 " + rowNo + " 帧失败数「failCount」= " + failCount + " 大于请求数「reqCount」= " + reqCount
                + "，两值矛盾；请核对执行器归帧逻辑（每帧只统计当秒自身的请求与失败），修正后重新上报";
    }

    public static String durationSumIllegal(int rowNo, double value) {
        return "第 " + rowNo + " 帧的「durationSumMs」= " + value + " 非法——响应耗时合计（毫秒）不能为负"
                + "（示例 8300）；请修正后重新上报";
    }

    public static String duplicateAnchorInBatch(int rowNo, long secondOffset, String stepKey) {
        return "第 " + rowNo + " 帧的「secondOffset」= " + secondOffset + " 与步骤「" + stepKey
                + "」组合和本批前序帧重复——同批内「secondOffset+步骤」必须唯一；"
                + "请把重复帧合并为一帧后重新上报，或到" + RUNS_ENTRY + "核对已入库行再定位错乱来源";
    }

    public static String replayMismatch(int rowNo, long secondOffset, String stepKey,
                                        double storedValue, double submittedValue) {
        return "第 " + rowNo + " 帧「secondOffset」= " + secondOffset + " 步骤「" + stepKey
                + "」已入库吞吐 " + storedValue + "，本次提交 " + submittedValue
                + "，重放内容不一致，疑似跨运行错投或补报旧秒；请核对「runId」是否为本次运行编号"
                + "（可在" + RUNS_ENTRY + "点开运行行查看），用正确编号重新上报";
    }

    public static String eventKindBlank(int rowNo) {
        return "第 " + rowNo + " 条事件的「eventKind」为空，事件流无法归档；请补填协议约定的类型值"
                + "（示例 start、finish、error）后重新上报";
    }

    public static String eventOffsetIllegal(int rowNo, long value) {
        return "第 " + rowNo + " 条事件的「secondOffset」= " + value + " 非法——秒偏移从 0 起计数；"
                + "请修正后重新上报";
    }

    public static String eventDetailTooLong(int rowNo, int length, int cap) {
        return "第 " + rowNo + " 条事件正文共 " + length + " 字符，超过「detail」单条上限 " + cap
                + " 字符；请在执行器侧先做摘要（如保留首行报错并省略重复堆栈）再重新上报";
    }

    public static String runNotFound(long runId, long projectId) {
        return "runId= " + runId + " 的压测运行不存在或不属于项目 projectId= " + projectId
                + "，采集数据无法入库；请到" + RUNS_ENTRY + "核对该运行编号是否属于本项目，用正确「runId」重新上报";
    }

    public static String runFinished(long runId) {
        return "runId= " + runId + " 的压测运行已结束，停压后的迟到帧不再写入曲线数据；"
                + "需要继续采集请到" + WORKBENCH_ENTRY + "新建一次运行，并用新的「runId」上报";
    }
}
