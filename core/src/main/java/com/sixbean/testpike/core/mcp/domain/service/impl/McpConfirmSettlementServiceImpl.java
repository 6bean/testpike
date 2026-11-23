package com.sixbean.testpike.core.mcp.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmGrantTicket;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmOutcome;
import com.sixbean.testpike.core.mcp.domain.component.McpConfirmEnvelope;
import com.sixbean.testpike.core.mcp.domain.component.McpConfirmGrantStore;
import com.sixbean.testpike.core.mcp.domain.component.McpConfirmLandingHandler;
import com.sixbean.testpike.core.mcp.domain.service.McpConfirmSettlementService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Confirmation loop over the registered landing handlers (strategy map, no branching).
 * <p>Order of checks is deliberate: decision word first, then atomic consumption, then
 * landing — so a malformed request never burns a grant while a burned grant always
 * corresponds to a recorded human decision (audit after either terminal path).
 */
@Service
public class McpConfirmSettlementServiceImpl implements McpConfirmSettlementService {

    static final String DECISION_ACCEPT = "accept";
    static final String DECISION_REJECT = "reject";
    static final String AUDIT_TYPE = "mcp";
    static final String AUDIT_ACCEPT = "mcp_confirm_accept";
    static final String AUDIT_REJECT = "mcp_confirm_reject";

    private final McpConfirmGrantStore grantStore;
    private final Map<String, McpConfirmLandingHandler> landingHandlers;
    private final AuditLogService auditLogService;

    public McpConfirmSettlementServiceImpl(McpConfirmGrantStore grantStore,
                                          Set<McpConfirmLandingHandler> handlers,
                                          AuditLogService auditLogService) {
        this.grantStore = grantStore;
        this.auditLogService = auditLogService;
        this.landingHandlers = new LinkedHashMap<>();
        for (McpConfirmLandingHandler handler : handlers) {
            McpConfirmLandingHandler previous = this.landingHandlers.putIfAbsent(handler.domain(), handler);
            if (previous != null) {
                throw new IllegalStateException("duplicate MCP confirm landing handler for domain "
                        + handler.domain());
            }
        }
    }

    @Override
    public McpConfirmGrantTicket issueGrant(String domain, String payloadJson, String summary,
                                            long projectId, Long actorId) {
        if (domain == null || domain.isBlank()
                || payloadJson == null || payloadJson.isBlank()
                || summary == null || summary.isBlank()) {
            throw new ServiceException("发起会话内确认缺少必要信息（域、载荷或草案摘要为空）：请在写工具调用时补全草案的域、载荷与摘要后重新发起（正确写法：三项均非空）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!landingHandlers.containsKey(domain)) {
            // Fail-closed: opening a gate with no settling path would strand the draft.
            throw new ServiceException("写通道 " + domain + " 尚未接入会话内确认闭环，无法发起确认码：请改用已接入的写通道（清单见「MCP 服务」页「工具勾选」弹窗），或登录后在「AI 对话」面板的草案卡中确认",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return grantStore.issue(new McpConfirmEnvelope(domain, payloadJson, summary, projectId, actorId));
    }

    @Override
    public McpConfirmOutcome confirm(String confirmRef, String decision, String reason, Long operatorId) {
        if (confirmRef == null || confirmRef.isBlank()) {
            throw new ServiceException("确认码 confirm_ref 为空，无法完成确认：请重新发起写操作获取带 confirm_ref 的回执，再调用 mcp.review.confirm 完成确认",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String normalized = decision == null ? "" : decision.trim().toLowerCase();
        if (!DECISION_ACCEPT.equals(normalized) && !DECISION_REJECT.equals(normalized)) {
            // Guard before consumption: an illegal word must not burn the grant.
            throw new ServiceException("确认决定只接受 accept（接受）或 reject（拒绝），本次传入 decision=" + decision + " 未生效，确认码仍然可用：请调用 mcp.review.confirm 并改传 decision=accept 或 reject",
                    BizErrorCode.VALIDATION_ERROR);
        }
        McpConfirmEnvelope envelope = grantStore.take(confirmRef);
        if (envelope == null) {
            // Covers expiry, forged references and every second presentation (replay,
            // UI-channel race): one refusal sentence, no enumeration oracle.
            throw new ServiceException("确认码无效、已过期或已被处理：如仍需变更，请重新调用写工具获取新的 confirm_ref 并在过期前确认（有效期由配置键 mcp.confirm.ttl-seconds 设置）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        boolean landed;
        if (DECISION_ACCEPT.equals(normalized)) {
            McpConfirmLandingHandler handler = landingHandlers.get(envelope.domain());
            try {
                handler.land(envelope);
            } catch (ServiceException e) {
                throw e;
            } catch (Exception e) {
                throw new ServiceException("草案落地失败：" + e.getMessage() + "，确认码已作废：请修正上述点名原因后重新发起写操作获取新确认码；字段规范可在「MCP 服务」页「工具勾选」弹窗的工具说明查看",
                        BizErrorCode.VALIDATION_ERROR);
            }
            landed = true;
        } else {
            landed = false;
        }
        recordAudit(confirmRef, envelope, normalized, reason, operatorId);
        return new McpConfirmOutcome(envelope.domain(), normalized, landed,
                landed ? "已按草案执行：" + envelope.summary()
                        : "已拒绝该草案，未做任何变更：" + envelope.summary());
    }

    private void recordAudit(String confirmRef, McpConfirmEnvelope envelope,
                             String normalized, String reason, Long operatorId) {
        Map<String, Object> before = new HashMap<>();
        before.put("summary", envelope.summary());
        before.put("domain", envelope.domain());
        Map<String, Object> after = new HashMap<>();
        after.put("decision", normalized);
        after.put("channel", "session");
        after.put("reason", reason);
        after.put("projectId", envelope.projectId());
        auditLogService.record(new AuditLogInput(operatorId, envelope.projectId(), AUDIT_TYPE,
                confirmRef, DECISION_ACCEPT.equals(normalized) ? AUDIT_ACCEPT : AUDIT_REJECT,
                before, after));
    }
}
