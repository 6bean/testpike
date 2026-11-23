package com.sixbean.testpike.aiservice.linkage.domain.service.impl;

import com.sixbean.testpike.aiservice.aichat.domain.component.ChatRepairDraft;
import com.sixbean.testpike.aiservice.aichat.domain.service.RepairProposalGenerator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.linkage.domain.service.LinkageProposalDraftService;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers one script-repair request through the REUSED proposal kernel —
 * {@link RepairProposalGenerator} ( proposal half-chain, same prompt
 * vocabulary and action-JSON gate as the B4 auto-repair loop; red
 * line: one generator, never a second implementation). The outcome always
 * becomes a snapshot on the core ledger via
 * {@code POST /internal/linkage-proposals}: a validated draft lands as
 * pending content, an unusable run lands honestly with its plain reason —
 * a pending draft that vanishes silently would look ignorable forever.
 * <p>Anchor note: the {@code case->script} edge selects scripts by their
 * owning case, so the kernel's script read keys on {@code upstreamObjectId}
 * (the case). {@code downstreamObjectId} (tp_script row id) is carried only
 * as the ledger anchor of the snapshot row.
 * <p>Nothing is written downstream here: landing the draft into a new script
 * version happens only behind the one-click grouped-card accept
 * (red line: AI pre-disposes, human confirms).
 */
@Service
public class LinkageProposalDraftServiceImpl implements LinkageProposalDraftService {

    private static final Logger log =
            LoggerFactory.getLogger(LinkageProposalDraftServiceImpl.class);

    private final RepairProposalGenerator proposalGenerator;
    private final CoreGateway coreGateway;

    public LinkageProposalDraftServiceImpl(RepairProposalGenerator proposalGenerator,
                                           CoreGateway coreGateway) {
        this.proposalGenerator = proposalGenerator;
        this.coreGateway = coreGateway;
    }

    @Override
    public void answer(LinkageProposalRequestMessage message) {
        long caseId = message.upstreamObjectId();
        ChatRepairDraft draft = proposalGenerator.generate(
                message.projectId(), caseId, describeStaleness(message));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("impactId", message.impactId());
        snapshot.put("downstreamObjectType", message.downstreamObjectType());
        snapshot.put("downstreamObjectId", message.downstreamObjectId());
        if (draft.valid()) {
            snapshot.put("proposalJson", draft.actionJson());
            snapshot.put("schemaVersion", draft.schemaVersion());
            snapshot.put("modelName", draft.modelName());
            snapshot.put("baseScriptVersion", draft.baseVersion());
        } else {
            snapshot.put("rejectReason", draft.invalidReason());
            // Journal WHICH script version the refusal was computed against
            // when the generator got that far; an unknown anchor stays
            // absent (absent = honestly unknown, never a fabricated zero).
            if (draft.baseVersion() > 0) {
                snapshot.put("baseScriptVersion", draft.baseVersion());
            }
        }
        Map<String, Object> ack = coreGateway.postLinkageProposal(snapshot);
        log.info("linkage proposal answered impactId={} caseId={} scriptId={} valid={} ack={}",
                message.impactId(), caseId, message.downstreamObjectId(),
                draft.valid(), ack);
    }

    /** Plain-language staleness note the repair prompt reads as its error context. */
    private String describeStaleness(LinkageProposalRequestMessage message) {
        return "上游用例 #" + message.upstreamObjectId() + " 已编辑保存新版本 v"
                + message.upstreamVersion() + "，脚本步骤可能与用例当前内容不一致，"
                + "请按用例的最新步骤修复脚本动作。";
    }
}
