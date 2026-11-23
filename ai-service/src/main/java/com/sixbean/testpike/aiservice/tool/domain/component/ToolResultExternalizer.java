package com.sixbean.testpike.aiservice.tool.domain.component;

/**
 * AP-1(1) storage seam for the verbatim full text of an oversized tool result
 * . The budget folder keeps the head/tail window in the dialog
 * and hands the whole payload here; whatever comes back is treated as an
 * opaque retrieval anchor written into the tool message pointer.
 * <p>Implementations must be scope-aware (task/conversation visibility) — the
 * retrieval meta-tool resolves anchors through the same owner rules, and a
 * cross-owner read is refused with a trace. Returning {@code null} or a blank
 * anchor means "nothing stored" and the folder degrades honestly to a bare
 * window (never a silent full-text loss claim). The S3 grant-backed
 * implementation lands in the next segment; until then no bean exists and the
 * folder runs window-only.
 */
public interface ToolResultExternalizer {

    /**
 * Persist the full tool output verbatim.
 *
 * @param fullText original tool output (already budget-violating)
 * @param taskId generation task owner scope, nullable = dialog context
 * @param projectId server-carried project segment, nullable
 * @return retrieval anchor id, or null/blank when nothing was stored
 */
    String store(String fullText, Long taskId, Long projectId);
}
