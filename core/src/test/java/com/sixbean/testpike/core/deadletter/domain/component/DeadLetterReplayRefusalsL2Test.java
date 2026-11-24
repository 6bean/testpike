package com.sixbean.testpike.core.deadletter.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R63 contract for the dead-letter replay/void doors: every refusal echoes the
 * offending ledger id, status or queue name, names the on-screen repair entry
 * (the dead-letter queue page) and keeps the legacy capability clause
 * contiguous so the existing service-test assertions stay green.
 */
class DeadLetterReplayRefusalsL2Test {

    private static void assertKeepsLegacy(String message, String legacy) {
        assertTrue(message.contains(legacy),
                "legacy clause must stay contiguous. actual: " + message);
    }

    private static void assertNamesEntry(String message) {
        assertTrue(message.contains("「死信队列」"),
                "message must name the on-screen repair entry. actual: " + message);
    }

    @Test
    void entityGoneEchoesLedgerAndNamesVoidEntry() {
        String msg = DeadLetterReplayRefusals.entityGone(8801L, "case-ai.dlq");
        assertKeepsLegacy(msg, "已禁止补投");
        assertTrue(msg.contains("实体已删除"), "must name the blocking class. actual: " + msg);
        assertTrue(msg.contains("8801") && msg.contains("case-ai.dlq"),
                "must echo ledger id and queue. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void voidedCannotReplayEchoesLedger() {
        String msg = DeadLetterReplayRefusals.voidedCannotReplay(8802L);
        assertKeepsLegacy(msg, "该记录已作废，不能补投");
        assertTrue(msg.contains("8802"), "must echo ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void alreadyReplayedEchoesCurrentStatus() {
        String msg = DeadLetterReplayRefusals.alreadyReplayed("replayed", 8803L);
        assertKeepsLegacy(msg, "该消息已补投");
        assertTrue(msg.contains("replayed") && msg.contains("8803"),
                "must echo status and ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void staleWindowExplainsReExecutionAndPointsToConfirm() {
        String msg = DeadLetterReplayRefusals.staleNeedsConfirm(8804L, 24);
        assertKeepsLegacy(msg, "重新执行");
        assertTrue(msg.contains("24") && msg.contains("8804"),
                "must echo window hours and ledger id. actual: " + msg);
        assertTrue(msg.contains("confirmReexecute"), "must name the parameter. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void claimLostExplodesConcurrencyAndPointsToList() {
        String msg = DeadLetterReplayRefusals.claimLost(8805L);
        assertKeepsLegacy(msg, "该消息已补投（同一行并发仅放行一次补投），本次请求未执行");
        assertTrue(msg.contains("8805"), "must echo ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void sendFailedEchoesCauseAndPromisesCleanRetry() {
        String msg = DeadLetterReplayRefusals.sendFailed("connection refused", 8806L);
        assertKeepsLegacy(msg, "补投发送失败，该记录已退回待处理状态，未消耗补投轮次；原因：");
        assertTrue(msg.contains("connection refused") && msg.contains("8806"),
                "must echo broker cause and ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void batchBannedClassPointsToRowVoid() {
        String msg = DeadLetterReplayRefusals.batchBannedClass("case-ai.dlq");
        assertKeepsLegacy(msg, "实体已删除类消息禁止补投");
        assertTrue(msg.contains("case-ai.dlq"), "must echo queue name. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void replayedCannotVoidEchoesLedger() {
        String msg = DeadLetterReplayRefusals.replayedCannotVoid(8807L);
        assertKeepsLegacy(msg, "该消息已补投，不能再作废");
        assertTrue(msg.contains("8807"), "must echo ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void alreadyVoidedPointsToVoidReasonColumn() {
        String msg = DeadLetterReplayRefusals.alreadyVoided(8808L);
        assertKeepsLegacy(msg, "该记录已作废，不重复执行；作废原因和操作人已保留在账面上");
        assertTrue(msg.contains("8808"), "must echo ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void voidUnsupportedStatusEchoesRawValue() {
        String msg = DeadLetterReplayRefusals.voidUnsupportedStatus("failed", 8809L);
        assertKeepsLegacy(msg, "不支持作废操作");
        assertTrue(msg.contains("failed") && msg.contains("8809"),
                "must echo the raw status and ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }

    @Test
    void ledgerNotFoundEchoesIdAndNamesRefreshEntry() {
        String msg = DeadLetterReplayRefusals.ledgerNotFound(8810L);
        assertKeepsLegacy(msg, "死信账目不存在（id= 8810）");
        assertNamesEntry(msg);
    }

    @Test
    void routingUnresolvableEchoesQueueAndPointsToVoid() {
        String msg = DeadLetterReplayRefusals.routingUnresolvable("legacy-queue", 8811L);
        assertKeepsLegacy(msg, "无法确定补投目标");
        assertTrue(msg.contains("legacy-queue") && msg.contains("8811"),
                "must echo queue name and ledger id. actual: " + msg);
        assertNamesEntry(msg);
    }
}
