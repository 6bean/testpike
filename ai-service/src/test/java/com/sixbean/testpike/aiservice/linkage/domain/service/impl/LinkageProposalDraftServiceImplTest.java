package com.sixbean.testpike.aiservice.linkage.domain.service.impl;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatRepairDraft;
import com.sixbean.testpike.aiservice.aichat.domain.service.RepairProposalGenerator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.linkage.domain.service.LinkageProposalDraftService;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 for the snapshot shape the proposal leg posts back .
 * Pinned contracts: a valid draft carries the action JSON, schema version,
 * model attribution and the base script version the draft was cut against;
 * a rejected run is persisted HONESTLY with its plain reason instead of
 * vanishing; the ledger anchors (impact + downstream member) ride along in
 * both shapes; the generator is asked for the script through the owning case
 * (upstream anchor), never through a re-implementation.
 */
class LinkageProposalDraftServiceImplTest {

    private static LinkageProposalRequestMessage request() {
        return new LinkageProposalRequestMessage(501L, "case->script", "case", 42L, 7,
                "script", 4201L, 3L, 1_700_000_000_000L);
    }

    @Test
    void validDraft_postsContentWithLedgerAnchors() {
        RepairProposalGenerator generator = mock(RepairProposalGenerator.class);
        CoreGateway gateway = mock(CoreGateway.class);
        when(generator.generate(eq(3L), eq(42L), any())).thenReturn(
                ChatRepairDraft.proposed("[{\"action\":\"click\"}]", "v2", "glm-4-plus",
                        "AI 修复草案", 42L, 9));
        LinkageProposalDraftService service =
                new LinkageProposalDraftServiceImpl(generator, gateway);

        service.answer(request());

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).postLinkageProposal(body.capture());
        Map<String, Object> snapshot = body.getValue();
        assertEquals(501L, snapshot.get("impactId"));
        assertEquals("script", snapshot.get("downstreamObjectType"));
        assertEquals(4201L, snapshot.get("downstreamObjectId"));
        assertEquals("[{\"action\":\"click\"}]", snapshot.get("proposalJson"));
        assertEquals("v2", snapshot.get("schemaVersion"));
        assertEquals("glm-4-plus", snapshot.get("modelName"));
        assertEquals(9, snapshot.get("baseScriptVersion"));
        assertFalse(snapshot.containsKey("rejectReason"), "有效草案不携带拒绝原因");
    }

    @Test
    void rejectedDraft_postsReasonHonestly_withoutContent() {
        RepairProposalGenerator generator = mock(RepairProposalGenerator.class);
        CoreGateway gateway = mock(CoreGateway.class);
        when(generator.generate(anyLong(), anyLong(), any())).thenReturn(
                ChatRepairDraft.rejected("动作 JSON 校验失败：缺少 action 字段"));
        LinkageProposalDraftService service =
                new LinkageProposalDraftServiceImpl(generator, gateway);

        service.answer(request());

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).postLinkageProposal(body.capture());
        Map<String, Object> snapshot = body.getValue();
        assertEquals(501L, snapshot.get("impactId"));
        assertEquals("动作 JSON 校验失败：缺少 action 字段", snapshot.get("rejectReason"));
        assertNull(snapshot.get("proposalJson"), "拒绝快照不得携带草案内容");
        assertTrue(String.valueOf(snapshot.get("rejectReason")).length() > 0,
                "拒绝必须留下人话原因，pending 草案禁无痕消失");
    }

    @Test
    void rejectedDraft_anchorsTheScriptVersionTheRefusalWasComputedAgainst() {
        RepairProposalGenerator generator = mock(RepairProposalGenerator.class);
        CoreGateway gateway = mock(CoreGateway.class);
        // Refusal raised after the script was read: the draft keeps the
        // version anchor it was computed against (audit anchor duty).
        when(generator.generate(anyLong(), anyLong(), any())).thenReturn(
                new ChatRepairDraft(false, null, null, null, null, 0L, 7,
                        "动作 JSON 校验失败：缺少 action 字段"));
        LinkageProposalDraftService service =
                new LinkageProposalDraftServiceImpl(generator, gateway);

        service.answer(request());

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).postLinkageProposal(body.capture());
        Map<String, Object> snapshot = body.getValue();
        assertEquals(7, snapshot.get("baseScriptVersion"),
                "拒绝快照必须点名拒答是针对哪一版脚本算的（审计锚+验证谱鲁棒性）");
        assertEquals("动作 JSON 校验失败：缺少 action 字段", snapshot.get("rejectReason"));
    }

    @Test
    void rejectedDraft_withoutKnownAnchor_postsNoFakeVersion() {
        RepairProposalGenerator generator = mock(RepairProposalGenerator.class);
        CoreGateway gateway = mock(CoreGateway.class);
        when(generator.generate(anyLong(), anyLong(), any())).thenReturn(
                new ChatRepairDraft(false, null, null, null, null, 0L, 0,
                        "读取当前脚本失败，无法给出可信修复草案"));
        LinkageProposalDraftService service =
                new LinkageProposalDraftServiceImpl(generator, gateway);

        service.answer(request());

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).postLinkageProposal(body.capture());
        assertFalse(body.getValue().containsKey("baseScriptVersion"),
                "脚本未读到时不得伪造版本锚，缺席即如实未知");
    }
}
