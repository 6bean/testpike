package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.tool.api.dto.ToolConfirmTicket;
import com.sixbean.testpike.core.tool.api.dto.ToolTestRunResult;


public interface ToolExecutionService {

    
    ToolTestRunResult testRun(Long operatorId, Long toolId, String inputJson);

    
    default ToolTestRunResult invoke(Long actorId, Long taskId, Long toolId, String inputJson) {
        return invoke(actorId, taskId, toolId, inputJson, null);
    }

    
    default ToolTestRunResult invoke(Long actorId, Long taskId, Long toolId, String inputJson,
                                     String confirmToken) {
        return invoke(actorId, taskId, toolId, inputJson, confirmToken, null);
    }

    /**
 * Carrier-carried project segment overload .
 * <p>System-level tools (tp_tool.project_id NULL, e.g. the dialog OCR read
 * doors) own no project fact, so the identity guard needs the segment the
 * server asserted when building the invoke carrier — a field the model can
 * never write. Null = no carrier anchor (async generation link): any
 * projectId claim in the payload stays refused, zero relaxation.
 *
 * @param assertedProjectId server-asserted project segment carried on the invoke
 * carrier (nullable = no anchor; project claims then stay refused)
 */
    ToolTestRunResult invoke(Long actorId, Long taskId, Long toolId, String inputJson,
                             String confirmToken, Long assertedProjectId);

    
    ToolConfirmTicket issueConfirmToken(Long operatorId, Long toolId);
}
