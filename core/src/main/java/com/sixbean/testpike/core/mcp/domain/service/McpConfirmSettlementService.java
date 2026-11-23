package com.sixbean.testpike.core.mcp.domain.service;

import com.sixbean.testpike.core.mcp.api.dto.McpConfirmGrantTicket;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmOutcome;

/**
 * Human-confirmation loop for MCP write tools, settled inside the agent conversation.
 * <p>The red line is untouched — a write still needs exactly one human decision —
 * only the channel moves: the write tool hands back a plain-language draft digest plus
 * a one-shot reference, and this service executes or cancels the draft when the user's
 * agent presents the decision here. The UI draft card stays as the fallback channel;
 * both channels share this queue and the first confirmed presentation wins.
 */
public interface McpConfirmSettlementService {

    /**
     * Open the confirmation gate: bind the drafted write to a one-shot reference.
     * Refused before anything is stored when the domain has no registered landing
     * handler (a gate that cannot settle must not open).
     */
    McpConfirmGrantTicket issueGrant(String domain, String payloadJson, String summary,
                                     long projectId, Long actorId);

    /**
     * Present the decision: {@code accept} lands the drafted write verbatim,
     * {@code reject} cancels it with zero writes. Either way the reference burns on
     * success of this call, the decision is audited with channel=session, and a second
     * presentation is refused in plain words. An illegal decision word is refused
     * before the reference is consumed so the caller can retry.
     */
    McpConfirmOutcome confirm(String confirmRef, String decision, String reason, Long operatorId);
}
