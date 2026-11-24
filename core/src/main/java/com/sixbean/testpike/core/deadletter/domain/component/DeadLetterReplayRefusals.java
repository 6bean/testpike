package com.sixbean.testpike.core.deadletter.domain.component;

/**
 * Single-source refusal wording for the dead-letter replay/void doors under
 * the R63 smart-guidance contract: every sentence echoes the offending
 * ledger id, status, queue name or broker cause, quotes the on-screen repair
 * entry in 「」, and keeps the legacy capability clause contiguous so the
 * existing service/controller assertions hold.
 *
 * <p>Quoted literals must match the shipped frontend wording (the dead-letter
 * queue page under the system menu registers replay and void actions). Keep
 * them in sync when the screens change.
 */
public final class DeadLetterReplayRefusals {

    /** Repair entry quoted for replay/void/ledger lookups. */
    public static final String DEAD_LETTER_ENTRY = "「死信队列」页";

    private DeadLetterReplayRefusals() {
    }

    public static String entityGone(long ledgerId, String queueName) {
        return "该消息关联的业务实体已删除，补投会再次进入死信，已禁止补投（账目 id= " + ledgerId
                + "，队列= " + queueName + "）；请先修复实体引用，或在" + DEAD_LETTER_ENTRY
                + "对该行点击「作废」并填写作废原因";
    }

    public static String voidedCannotReplay(long ledgerId) {
        return "该记录已作废，不能补投；作废记录保留全部留痕（账目 id= " + ledgerId
                + "），如需重放请在" + DEAD_LETTER_ENTRY + "核对作废原因后由维护方另案核实";
    }

    public static String alreadyReplayed(String status, long ledgerId) {
        return "该消息已补投（状态= " + status + "，账目 id= " + ledgerId
                + "），同一条账目不重复补投；如需再次发送请在" + DEAD_LETTER_ENTRY
                + "刷新列表确认状态后由维护方核实另案处理";
    }

    public static String staleNeedsConfirm(long ledgerId, int windowHours) {
        return "该消息已超过 " + windowHours + " 小时（账目 id= " + ledgerId
                + "），下游幂等保护已过期，补投将作为全新消息重新执行业务动作；"
                + "请在" + DEAD_LETTER_ENTRY + "的补投确认中勾选接受重新执行语义"
                + "（参数 confirmReexecute=true）后再次提交";
    }

    public static String claimLost(long ledgerId) {
        return "该消息已补投（同一行并发仅放行一次补投），本次请求未执行（账目 id= " + ledgerId
                + "）；请在" + DEAD_LETTER_ENTRY + "刷新列表查看该行当前状态";
    }

    public static String sendFailed(String cause, long ledgerId) {
        return "补投发送失败，该记录已退回待处理状态，未消耗补投轮次；原因：" + cause
                + "（账目 id= " + ledgerId + "）；请在消息队列服务恢复后于" + DEAD_LETTER_ENTRY
                + "对该行重新点击「补投」";
    }

    public static String batchBannedClass(String queueName) {
        return "实体已删除类消息禁止补投（补投必然再次死信，只消耗重试额度，队列= " + queueName
                + "）；请在" + DEAD_LETTER_ENTRY + "逐条对该行点击「作废」并填写原因，"
                + "或先修复数据再按其它分型补投";
    }

    public static String replayedCannotVoid(long ledgerId) {
        return "该消息已补投，不能再作废（账目 id= " + ledgerId
                + "）；如需终止其后续影响请在" + DEAD_LETTER_ENTRY
                + "查看该行补投结果后由维护方处理";
    }

    public static String alreadyVoided(long ledgerId) {
        return "该记录已作废，不重复执行；作废原因和操作人已保留在账面上（账目 id= " + ledgerId
                + "），可在" + DEAD_LETTER_ENTRY + "查看该行的「作废原因」列";
    }

    public static String voidUnsupportedStatus(String status, long ledgerId) {
        return "该记录当前状态（" + status + "，账目 id= " + ledgerId
                + "）不支持作废操作；请在" + DEAD_LETTER_ENTRY
                + "刷新列表，按该行最新状态选择补投或作废动作";
    }

    public static String ledgerNotFound(long ledgerId) {
        return "死信账目不存在（id= " + ledgerId + "）；请刷新" + DEAD_LETTER_ENTRY
                + "列表后重试，若列表也没有该行，说明该账目已被清理";
    }

    public static String routingUnresolvable(String queueName, long ledgerId) {
        return "该记录缺少原始投递路由信息且队列名不符合 .dlq 命名约定，无法确定补投目标"
                + "（账目 id= " + ledgerId + "，队列= " + queueName + "）；"
                + "请在" + DEAD_LETTER_ENTRY + "对该行点击「作废」并填写原因，由人工核实处理";
    }
}
