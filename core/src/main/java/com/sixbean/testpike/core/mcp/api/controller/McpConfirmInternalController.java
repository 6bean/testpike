package com.sixbean.testpike.core.mcp.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmDecisionInput;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmGrantTicket;
import com.sixbean.testpike.core.mcp.api.dto.McpConfirmOutcome;
import com.sixbean.testpike.core.mcp.api.dto.McpScriptWritebackDraftInput;
import com.sixbean.testpike.core.mcp.domain.service.McpConfirmSettlementService;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackEvidence;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal doors for the MCP in-session confirmation loop .
 * <p>Thin by design: request shaping and the landing-domain identity live here, every
 * security and settle decision delegates to the single settlement service. The script
 * domain assembles its payload in exactly the shape the landing handler re-reads, so
 * an accepted draft runs the one write-back path with no second route.
 */
@RestController
@RequestMapping("/internal/mcp-confirm")
public class McpConfirmInternalController {

    /** Landing registry key of the script write-back domain (matches the MCP tool name). */
    static final String DOMAIN_SCRIPT_WRITEBACK = "script.writeback";

    private final McpConfirmSettlementService settlementService;
    private final ObjectMapper objectMapper;

    public McpConfirmInternalController(McpConfirmSettlementService settlementService,
                                        ObjectMapper objectMapper) {
        this.settlementService = settlementService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/drafts/script-writeback")
    public Response<McpConfirmGrantTicket> draftScriptWriteback(
            @RequestBody McpScriptWritebackDraftInput input) {
        Long operator = OperatorContext.currentOperatorId();
        ScriptWritebackInput writeback = new ScriptWritebackInput(input.caseId(),
                input.schemaVersion(), input.actionJson(), input.evidence(),
                input.expectedVersion(), operator);
        return Response.builderSuccess(settlementService.issueGrant(
                DOMAIN_SCRIPT_WRITEBACK, serialize(writeback), summaryOf(input),
                input.projectId(), operator));
    }

    @PostMapping("/confirm")
    public Response<McpConfirmOutcome> confirm(@RequestBody McpConfirmDecisionInput input) {
        return Response.builderSuccess(settlementService.confirm(input.confirmRef(),
                input.decision(), input.reason(), OperatorContext.currentOperatorId()));
    }

    private String serialize(ScriptWritebackInput writeback) {
        try {
            return objectMapper.writeValueAsString(writeback);
        } catch (JsonProcessingException ex) {
            throw new ServiceException("脚本写回草案无法登记（载荷序列化失败），未产生任何变更：请按「工具勾选」弹窗中该工具的说明修正载荷后重新发起（示例：steps 传数组、caseId 传数字 ID）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Three-part plain-language digest for the conversation (11-16 ruling, experience
     *  detail 4): touched object / change points / blast radius. Never dumps the
     *  payload json — an external agent renders this text back to the user. */
    private static String summaryOf(McpScriptWritebackDraftInput input) {
        StringBuilder digest = new StringBuilder();
        digest.append("动什么：用例 #").append(input.caseId()).append(" 的自动化脚本。");
        digest.append("变更点：站外脚本将作为新版本写入");
        if (input.expectedVersion() != null) {
            digest.append("，期望基于版本 ").append(input.expectedVersion())
                  .append("（用例当前版本已变化时会被拒绝并提示重新获取）");
        } else {
            digest.append("，未指定期望版本（不做版本比对）");
        }
        ScriptWritebackEvidence evidence = input.evidence();
        if (evidence == null) {
            digest.append("；未附证据包，按站外自报分级。");
        } else {
            digest.append("；附证据包（跑于 ").append(evidence.ranAt())
                  .append("，通过 ").append(evidence.passed())
                  .append(" / 失败 ").append(evidence.failed())
                  .append("），按站外实证分级。");
        }
        digest.append("影响面：接受后脚本作为新版本落库、用例转「待评审」；"
                + "拒绝则草案作废、零变更；未在有效期内确认时草案自动失效。");
        return digest.toString();
    }
}
