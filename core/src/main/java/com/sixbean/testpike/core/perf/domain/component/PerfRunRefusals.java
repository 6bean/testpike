package com.sixbean.testpike.core.perf.domain.component;

/**
 * Single-source refusal wording for the perf run launch/finish/mark doors
 * under the R63 smart-guidance contract: every sentence echoes the offending
 * submitted value, quotes the on-screen repair entry in 「」 and keeps the
 * legacy capability clause contiguous so existing assertions hold.
 *
 * <p>Quoted literals must match the shipped frontend wording (load-test
 * workbench page, perf run ledger page, environment config page, system
 * settings). Keep them in sync when the screens change.
 */
public final class PerfRunRefusals {

    /** Repair entry quoted for launching or re-saving a scenario. */
    public static final String WORKBENCH_ENTRY = "「压测工作台」页";

    /** Repair entry quoted for stored runs, marks and status lookups. */
    public static final String RUNS_ENTRY = "「压测运行台账」页";

    private PerfRunRefusals() {
    }

    public static String scenarioMissing(long scenarioId) {
        return "压测场景不存在或不属于该项目，无法发起运行（场景 id= " + scenarioId + "）；"
                + "请到" + WORKBENCH_ENTRY + "确认场景属于当前项目后重新发起";
    }

    public static String envMissing(long envId) {
        return "压测发起所选环境不存在（环境 id= " + envId + "），可能已被删除；"
                + "请到「环境配置」页重新选择目标环境后再发起";
    }

    public static String envNotInProject(long envId, long projectId) {
        return "压测发起所选环境不属于该项目（环境 id= " + envId + "，当前项目 id= " + projectId
                + "），无法跨项目压测；请切换顶栏到环境所属项目，"
                + "或在「环境配置」页为本项目新建/选择环境";
    }

    public static String gateBlocked(String reason) {
        return "压测运行启动被安全闸拦截：" + reason
                + "；请按原因整改后到" + WORKBENCH_ENTRY + "重新发起，可先点「发起前检查」逐项查看闸门结论";
    }

    public static String durationCeiling(String declaredHours, int ceilingHours) {
        return "压测运行启动被时长上限拦截：声明时长 " + declaredHours + " 小时超过当前上限 " + ceilingHours
                + " 小时（长跑请拆分为多批短程运行）；上限可热更调整：在「系统设置」修改"
                + " perf.max-duration-hours（保存即生效）";
    }

    public static String unknownStatus(int status) {
        return "未知运行状态 status= " + status + "，可选=1 运行中/2 正常结束/3 熔断停/4 人工停；"
                + "请核对执行器结束上报或" + RUNS_ENTRY + "的「停止」动作参数后重发";
    }

    public static String statusNotTerminal(int status) {
        return "结束运行只接受终态（2 正常结束/3 熔断停/4 人工停），本次提交 status= " + status
                + "；运行中的压测请到" + RUNS_ENTRY + "点「停止」，由执行器落终态后再结束";
    }

    public static String finishNotFound(long runId, long projectId) {
        return "压测运行不存在、不属于该项目或已结束（runId= " + runId + "，项目 id= " + projectId
                + "）；请到" + RUNS_ENTRY + "核对运行编号与归属后重试";
    }

    public static String unknownMark(String mark) {
        return "未知运行标记 mark= " + mark + "，可选=GOLDEN 金跑/CANDIDATE 候选/VOID 作废；"
                + "请在" + RUNS_ENTRY + "的「标记」操作中选择三者之一";
    }

    public static String markNotFound(long runId) {
        return "压测运行不存在或不属于该项目（runId= " + runId + "）；请到" + RUNS_ENTRY
                + "选中目标运行后再打标";
    }

    public static String markWhileRunning(long runId, int status) {
        return "运行中的压测不可打标（runId= " + runId + "，当前状态= " + status
                + "），请等待运行结束（正常结束/熔断停/人工停）后再标记；运行状态可在" + RUNS_ENTRY + "查看";
    }

    public static String snapshotUnserializable() {
        return "运行快照序列化失败，本次发起已回滚、未写入运行记录；请检查场景的压测配置（pressure_config）"
                + "与步骤内容是否为合法数据，到" + WORKBENCH_ENTRY + "重新保存场景后再发起";
    }
}
