package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.mcp.domain.component.McpConfirmEnvelope;
import com.sixbean.testpike.core.mcp.domain.component.McpConfirmLandingHandler;
import com.sixbean.testpike.core.script.api.dto.ScriptWritebackInput;
import com.sixbean.testpike.core.script.domain.service.ScriptWritebackService;
import org.springframework.stereotype.Component;

/**
 * Landing seam that settles an accepted script write-back draft through the single
 * write-back path (no second persistence route; the channel identity matches the MCP
 * tool name so the confirmation loop stays generic).
 */
@Component
public class ScriptWritebackConfirmLanding implements McpConfirmLandingHandler {

    static final String DOMAIN = "script.writeback";

    private final ObjectMapper objectMapper;
    private final ScriptWritebackService scriptWritebackService;

    public ScriptWritebackConfirmLanding(ObjectMapper objectMapper,
                                         ScriptWritebackService scriptWritebackService) {
        this.objectMapper = objectMapper;
        this.scriptWritebackService = scriptWritebackService;
    }

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public void land(McpConfirmEnvelope envelope) {
        ScriptWritebackInput input;
        try {
            input = objectMapper.readValue(envelope.payloadJson(), ScriptWritebackInput.class);
        } catch (Exception e) {
            throw new ServiceException("脚本写回草案载荷无法解析，本次确认未执行落地——"
                    + "请在「AI 对话」重新发起脚本写回生成新草案后再确认，"
                    + "或在「用例管理」脚本编辑页直接保存脚本",
                    BizErrorCode.VALIDATION_ERROR);
        }
        scriptWritebackService.writeback(input);
    }
}
