package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Repair-proposal draft for the dialog chain . Same
 * two-state shape as the B4 auto-loop {@code RepairProposal} (valid = a
 * validated action JSON + human summary; invalid = a plain reason and the
 * caller emits nothing), widened with the anchors the confirmation chain
 * (knife 3b) needs to land it safely: which execution produced it, which case
 * and script version the proposal was cut against.
 * <p>A draft NEVER writes anything: landing happens only behind a confirm
 * token after the baseVersion is re-checked (red line: no change lands
 * without explicit user confirmation).
 *
 * @param valid true when the proposal passed the action-JSON gate
 * @param actionJson repaired action JSON (only when valid)
 * @param schemaVersion envelope version carried from the source script
 * @param modelName model that produced the proposal (card attribution)
 * @param summary human-readable one-liner for the draft card
 * @param caseId case the draft belongs to
 * @param baseVersion script version the proposal was cut against (lock anchor)
 * @param invalidReason plain reason when no trustworthy proposal exists
 */
public record ChatRepairDraft(boolean valid, String actionJson, String schemaVersion,
                              String modelName, String summary, long caseId,
                              int baseVersion, String invalidReason) {

    public static ChatRepairDraft proposed(String actionJson, String schemaVersion,
                                            String modelName, String summary,
                                            long caseId, int baseVersion) {
        return new ChatRepairDraft(true, actionJson, schemaVersion, modelName, summary,
                caseId, baseVersion, null);
    }

    public static ChatRepairDraft rejected(String reason) {
        return new ChatRepairDraft(false, null, null, null, null, 0L, 0, reason);
    }

    /**
     * Refusal raised after the script was read: keeps the version anchor so
     * the audit (and any verification window) can name WHICH script version
     * the refusal was computed against. A zero/negative version means the
     * anchor was never known — use the single-argument factory instead.
     */
    public static ChatRepairDraft rejected(String reason, int baseVersion) {
        return new ChatRepairDraft(false, null, null, null, null, 0L, baseVersion, reason);
    }
}
