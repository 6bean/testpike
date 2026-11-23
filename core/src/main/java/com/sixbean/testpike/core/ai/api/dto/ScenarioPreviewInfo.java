package com.sixbean.testpike.core.ai.api.dto;

import java.util.List;

/**
 * Persisted stage0 scenario preview row read back for the human
 * circle-selection step . Draft shape: nothing here has
 * produced a case yet.
 *
 * @param id tp_ai_scenario_preview primary key
 * @param batchId owning batch (nullable for single-line previews)
 * @param seq row order inside the submission
 * @param projectId owning project
 * @param requirementText requirement line original text
 * @param priority 0=P0 1=P1 2=P2
 * @param expansionEnabled K8 switch state at enumeration time
 * @param scenarios surviving scenario items
 * @param residualClaims disputes still open when the ring stopped
 * @param roundsUsed deliberation rounds actually run
 * @param modelCalls ring model-call segments consumed
 * @param converged final draft verified with zero disputes
 */
public record ScenarioPreviewInfo(long id, Long batchId, int seq, long projectId,
                                  String requirementText, int priority, boolean expansionEnabled,
                                  List<ScenarioPreviewItem> scenarios, List<String> residualClaims,
                                  int roundsUsed, int modelCalls, boolean converged) {
}
