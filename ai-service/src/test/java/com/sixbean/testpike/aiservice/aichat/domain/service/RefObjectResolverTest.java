package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.RefResolution;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Chip resolution edge contract complementing the
 * orchestrator-level spectra in ChatRunOrchestratorTest: the door being down
 * is a conservative refusal (never a silent drop — an unjournaled chip would
 * make the answer differ from what the user pinned), the defect path chains
 * through its owning case, and an empty chip list touches no door at all.
 */
class RefObjectResolverTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final RefObjectResolver resolver = new RefObjectResolver(gateway);

    @Test
    void emptyChipListPassesThroughWithoutAnyFetch() {
        RefResolution outcome = resolver.resolve(3L, List.of());
        assertFalse(outcome.refused());
        assertEquals("", outcome.contextBlock());
        verify(gateway, never()).getCase(anyLong());
    }

    @Test
    void doorOutageRefusesConservativelyNamingTheChip() {
        when(gateway.getCase(11L)).thenThrow(new IllegalStateException("connection refused"));
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("case", 11L, "登录冒烟")));
        assertTrue(outcome.refused());
        assertTrue(outcome.rejectionReason().contains("登录冒烟"), outcome.rejectionReason());
        assertTrue(outcome.rejectionReason().contains("暂时读不到"), outcome.rejectionReason());
    }

    @Test
    void defectDigestChainsThroughItsOwningCase() {
        when(gateway.getDefect(8L)).thenReturn(Map.of(
                "caseId", 11L, "defectKey", "PIKE-8", "defectStatus", "open"));
        when(gateway.getCase(11L)).thenReturn(Map.of(
                "projectId", 3L, "title", "支付回归", "caseNo", "C-0009"));
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("defect", 8L, "漏测缺陷")));
        assertFalse(outcome.refused());
        assertTrue(outcome.contextBlock().contains("[R1]"), outcome.contextBlock());
        assertTrue(outcome.contextBlock().contains("PIKE-8"), outcome.contextBlock());
        assertTrue(outcome.contextBlock().contains("支付回归"), outcome.contextBlock());
    }

    @Test
    void multipleChainsAreNumberedInOrder() {
        when(gateway.getCase(11L)).thenReturn(Map.of("projectId", 3L, "title", "甲"));
        when(gateway.getDocument(6L)).thenReturn(Map.of("projectId", 3L, "name", "乙",
                "fileType", "pdf"));
        RefResolution outcome = resolver.resolve(3L, List.of(
                new ChatObjectRef("case", 11L, "甲"),
                new ChatObjectRef("doc", 6L, "乙")));
        assertTrue(outcome.contextBlock().contains("[R1] 测试用例「甲」"), outcome.contextBlock());
        assertTrue(outcome.contextBlock().contains("[R2] 知识文档「乙」"), outcome.contextBlock());
    }

    /**
 * AO batch stage 1: a skill chip resolves against the project's
 * assembly set (the SAME source the palette offers and S5 injection
 * consumes — no second lookup chain). A skill the assembly does not carry
 * — retired, unbound, or another project's row — is a named refusal
 * (fail-closed posture shared with the E3 families).
 */
    @Test
    void skillChipVerifiesAgainstTheAssemblySetAndDigestsNameAndVersion() {
        when(gateway.getProjectAssembly(3L)).thenReturn(List.of(
                new SkillAssemblyRemoteItem(11L, "冒烟规范", 3, 1, 2, 5, "body", 0)));
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("skill", 11L, "冒烟规范")));
        assertFalse(outcome.refused());
        assertTrue(outcome.contextBlock().contains("技能「冒烟规范」"), outcome.contextBlock());
        assertTrue(outcome.contextBlock().contains("5"), outcome.contextBlock());
    }

    @Test
    void skillChipOutsideTheAssemblySetIsRefusedByName() {
        when(gateway.getProjectAssembly(3L)).thenReturn(List.of(
                new SkillAssemblyRemoteItem(11L, "冒烟规范", 3, 1, 2, 5, "body", 0)));
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("skill", 99L, "越权技能")));
        assertTrue(outcome.refused(), outcome.contextBlock());
        assertTrue(outcome.rejectionReason().contains("越权技能"), outcome.rejectionReason());
    }

    @Test
    void toolChipVerifiesAgainstTheActorAssemblyAndCarriesExecKindIntoDigest() {
        when(gateway.getProjectFcAssembly(3L, 7L)).thenReturn(List.of(
                new ToolFcAssemblyRemoteItem(21L, "订单查询", 2, 1, "{}", 5000, false)));
        RefResolution outcome = resolver.resolve(3L, 7L,
                List.of(new ChatObjectRef("tool", 21L, "订单查询")));
        assertFalse(outcome.refused());
        assertTrue(outcome.contextBlock().contains("工具「订单查询」"), outcome.contextBlock());
    }

    @Test
    void toolChipOutsideTheActorAssemblyIsRefusedByName() {
        when(gateway.getProjectFcAssembly(3L, 7L)).thenReturn(List.of());
        RefResolution outcome = resolver.resolve(3L, 7L,
                List.of(new ChatObjectRef("tool", 21L, "他项目工具")));
        assertTrue(outcome.refused(), outcome.contextBlock());
        assertTrue(outcome.rejectionReason().contains("他项目工具"), outcome.rejectionReason());
    }

    /**
 * AM batch (pinned-object grounding): a case chip carries its live
 * version line plus an [R1a] review-opinion section and an [R1b] failure
 * summary, so the model answers from facts instead of asking the user to
 * restate the case. Attribution labels ride the shared ExecutionLabels
 * mapping — the same words the diagnosis face uses.
 */
    @Test
    void caseChipCarriesVersionReviewOpinionsAndFailureSummary() {
        when(gateway.getCase(11L)).thenReturn(Map.of(
                "projectId", 3L, "title", "登录冒烟", "caseNo", "C-0011",
                "version", 7, "updatedAt", "2026-11-07T15:30:00"));
        when(gateway.getCaseReviewLogs(11L)).thenReturn(List.of(
                Map.of("operatorName", "王评审", "action", "需修改", "createdAt", "2026-11-06T10:00:00",
                        "remark", "断言缺少错误码校验"),
                Map.of("operatorName", "李评审", "action", "通过", "createdAt", "2026-11-01T09:00:00",
                        "remark", "  "),
                Map.of("operatorName", "赵评审", "action", "拒绝", "createdAt", "2026-10-30T08:00:00",
                        "remark", "步骤不可复现"),
                Map.of("operatorName", "钱评审", "action", "通过", "createdAt", "2026-10-29T08:00:00",
                        "remark", "第四条非空意见"),
                Map.of("operatorName", "孙评审", "action", "需修改", "createdAt", "2026-10-28T08:00:00",
                        "remark", "第五条超出默认上限三，不应入块")));
        when(gateway.getCaseExecHistory(11L, 3L, 1, 2)).thenReturn(Map.of(
                "list", List.of(Map.of(
                        "status", 4, "finishedAt", "2026-11-05T20:00:00", "batchName", "夜间回归",
                        "aiFailureClass", 2, "errorDetail", "元素定位失败：#submit 未找到")),
                "total", 9));
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("case", 11L, "登录冒烟")));
        assertFalse(outcome.refused(), outcome.rejectionReason());
        String block = outcome.contextBlock();
        assertTrue(block.contains("当前版本 v7，更新于 2026-11-07T15:30:00"), block);
        assertTrue(block.contains("[R1a]"), block);
        assertTrue(block.contains("断言缺少错误码校验"), block);
        assertTrue(block.contains("步骤不可复现"), block);
        assertFalse(block.contains("李评审"),
                "blank remark must not enter the section: " + block);
        assertFalse(block.contains("第五条超出默认上限三"),
                "opinions beyond the review-max cap must stay out: " + block);
        assertTrue(block.contains("第四条非空意见"),
                "the third non-blank opinion sits inside cap 3 and must enter: " + block);
        assertTrue(block.contains("[R1b]"), block);
        assertTrue(block.contains("失败"), block);
        assertTrue(block.contains("AI定位错误"), block);
        assertTrue(block.contains("夜间回归"), block);
    }

    /** Thresholds are hot-updatable K8 knobs: review-max=1 keeps only the newest opinion. */
    @Test
    void reviewOpinionCapIsReadFromTheHotConfigKey() {
        when(gateway.getCase(11L)).thenReturn(Map.of(
                "projectId", 3L, "title", "登录冒烟", "caseNo", "C-0011"));
        when(gateway.getSysConfig("ai.chat.pinned-grounding.review-max")).thenReturn("1");
        when(gateway.getCaseReviewLogs(11L)).thenReturn(List.of(
                Map.of("operatorName", "王评审", "action", "需修改", "createdAt", "2026-11-06T10:00:00",
                        "remark", "最新一条"),
                Map.of("operatorName", "赵评审", "action", "拒绝", "createdAt", "2026-10-30T08:00:00",
                        "remark", "旧一条应被上限挡下")));
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("case", 11L, "登录冒烟")));
        String block = outcome.contextBlock();
        assertTrue(block.contains("最新一条"), block);
        assertFalse(block.contains("旧一条应被上限挡下"),
                "cap 1 must drop the second opinion: " + block);
    }

    /**
 * Enrichment reads are fail-soft (self-adjudicated posture ①): a down
 * turn still runs — only the main getCase door keeps its refusal verdict.
 */
    @Test
    void reviewDoorOutageDegradesToNoteInsteadOfRefusingTheRun() {
        when(gateway.getCase(11L)).thenReturn(Map.of(
                "projectId", 3L, "title", "登录冒烟", "caseNo", "C-0011"));
        when(gateway.getCaseReviewLogs(11L)).thenThrow(new IllegalStateException("door down"));
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("case", 11L, "登录冒烟")));
        assertFalse(outcome.refused(), outcome.rejectionReason());
        assertTrue(outcome.contextBlock().contains("评审意见暂不可读"), outcome.contextBlock());
    }

    /** No opinions on record = no [R1a] section at all (digest stays honest). */
    @Test
    void emptyReviewSetEmitsNoOpinionSection() {
        when(gateway.getCase(11L)).thenReturn(Map.of(
                "projectId", 3L, "title", "登录冒烟", "caseNo", "C-0011"));
        when(gateway.getCaseReviewLogs(11L)).thenReturn(List.of());
        RefResolution outcome = resolver.resolve(3L,
                List.of(new ChatObjectRef("case", 11L, "登录冒烟")));
        assertFalse(outcome.refused(), outcome.rejectionReason());
        assertFalse(outcome.contextBlock().contains("[R1a]"), outcome.contextBlock());
        assertFalse(outcome.contextBlock().contains("[R1b]"), outcome.contextBlock());
    }
}
