package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.core.tool.api.dto.ToolDraftKindItem;
import com.sixbean.testpike.core.tool.api.dto.ToolFcAssemblyItem;

import java.util.List;


public interface ToolFcAssemblyService {

    
    List<ToolFcAssemblyItem> getAssemblyItems(Long projectId);

    /**
 * Actor-aware assembly : the five-way intersection
 * facets of project visibility and the execution-class switch apply on top
 * of published∩enabled.
 * <ul>
 * <li>non-member and non-admin callers get 40301 (horizontal privilege
 * refusal, zero content leak);</li>
 * <li>with {@code ai.chat.exec-enabled} off, EXECUTOR-shape tools leave
 * the candidate set (vertical cross-role spectrum);</li>
 * <li>a null actor keeps the legacy verdict untouched (stage 3 tightens
 * the identity-injection seam).</li>
 * </ul>
 *
 * @param projectId tp_project.id; 40401 when missing, 40301 when the actor
 * may not see the segment
 * @param actorId consuming user (dialog initiator); null = legacy verdict
 */
    List<ToolFcAssemblyItem> getAssemblyItems(Long projectId, Long actorId);

    /**
 * Draft-card routing facts of the project segment (tool-surface clearing
 * batch stage 2): name → declared draft objectType for every
 * published∩enabled tool whose exec spec carries one. The dialog emitter
 * consults this instead of memorising tool names, so publishing a new
 * write tool with {@code draftObjectType} in its exec spec is enough for
 * its calls to grow confirmable cards — zero orchestrator change.
 * Visibility filter is the SAME single-sourced read as the FC assembly
 * (project ∪ system-level union, R34), never a second query shape.
 */
    List<ToolDraftKindItem> getDraftKinds(Long projectId, Long actorId);
}
