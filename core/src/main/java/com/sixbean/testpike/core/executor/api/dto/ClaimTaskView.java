package com.sixbean.testpike.core.executor.api.dto;

import java.util.List;
import java.util.Map;

/**
 *
 * max-steps/max-loop/max-nest-depth/assert-timeout-sec/screenshot-mode/
 * @param mock Claim-side mock binding (03 protocol 2.3 trailing mock key): a
 * task_type=1 browser task whose case declares a PUBLISHED mock
 * space carries {spaceKey,baseUrl,snapshotVersion}. Gate,
 * address composition and version pin all come from
 * MockServingBindingResolver — the exact same single source the
 * api-test envelope assembly uses (no second copy). envBaseUrl
 * itself keeps the authoritative environment URL: the rewrite
 * toward the traffic plane belongs to the executor and must
 * happen AFTER the production-guard decision reads this value.
 * Unbound / draft / retired / other task types = null (the key
 * is not emitted; old claim shape untouched, old executors
 * ignore the field with zero drift).
 * @param sessionState face-2 login-state binding (03 protocol 2.3 trailing
 * key): a task_type=1 browser task whose action JSON declares
 * sessionState{name|roleScope} carries
 * {stateName,kind,storageState} materialised by
 * SessionStateSupport — the single translation source the
 * direct-API face already shares (red line 6, the executor
 * never re-derives). An unresolvable declaration emits
 * {stateName,refusal} without material: the claim round
 * still succeeds (one bad row cannot block the queue) and
 * the executor fails just this task with the relayed
 * wording. No declaration / non-browser task = null, key
 * not emitted (trailing zero-drift discipline, same as
 * mock). Old executors ignore the field.
 * ⚠️ storageState embeds session credentials plaintext —
 * no log line may print this view (red line 4, same as envVars).
 * @param elementAnchors WE batch S1 (03 protocol 2.3 trailing key): element ledger
 * snapshots for every {{ELEM:}} / by=element reference in the
 * action JSON — {allowCandidate, elements{name:[{elementId,
 * pageKey, state, confidence, anchors(ordered strongest first)}]}}.
 * No reference / non-browser task / dirty JSON = null, key not
 * emitted (trailing zero-drift discipline, same as sessionState);
 * old executors ignore the field. The executor never calls back
 * into core for ledger rows (execution-chain decoupling).
 *
 */
public record ClaimTaskView(long taskId, long caseId, String claimToken,
                            String schemaVersion, String actionJson, String envBaseUrl,
                            Long batchId, long timeoutMs,
                            Map<String, String> envVars, int taskType,
                            Map<String, Integer> scriptLimits,
                            Map<String, Object> execPolicy,
                            List<Map<String, Object>> connections,
                            Map<String, String> dataVars,
                            String browser,
                            Map<String, Object> visualBaselines,
                            com.sixbean.testpike.common.util.TlsPolicy tlsPolicy,
                            Map<String, String> sessionArtifacts,
                            Map<String, String> roleBindingContext,
                            Map<String, String> stepAliases,
                            Map<String, Object> perf,
                            com.sixbean.testpike.core.mock.api.dto.MockServingBinding mock,
                            com.sixbean.testpike.core.session.api.dto.SessionStateClaimBinding sessionState,
                            com.sixbean.testpike.core.webelement.api.dto.ElementAnchorsClaimBinding elementAnchors) {
}
