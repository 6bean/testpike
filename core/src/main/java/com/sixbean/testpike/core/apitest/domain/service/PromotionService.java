package com.sixbean.testpike.core.apitest.domain.service;

import com.sixbean.testpike.core.apitest.api.dto.FlowPromotionResultView;
import com.sixbean.testpike.core.apitest.api.dto.PromotionResultView;

/**
 * Sandbox request to regression case promotion service .
 * <p>Turns a tuned API-test request into a real quality-management asset:
 * one case (automationTarget=api, source=6) carrying one http.request step whose
 * expect object is mapped from the WS2 assertion form rows by
 * {@code PromotionStepMapper} (single source), plus badge write-back and the
 * latest debug-run verdict migrated as script first-trial evidence. Case creation
 * and script save deliberately go through the existing CaseService / ScriptService
 * chains — no parallel writing path (red line: no second system).
 */
public interface PromotionService {

    /**
     * Promote one request (all-or-nothing transaction).
     *
     * @param operatorId acting user (audit chain + script operator context)
     * @param requestId  sandbox request id
     * @return created case coordinates + source lineage echo
     * @throws com.sixbean.testpike.common.exception.ServiceException
     *         40401 request missing; 40001 assertion rows unmappable or an auth
     *         type the execution face cannot honour; 40904 already promoted
     *         (message names the existing case number — idempotent answer)
     */
    PromotionResultView promote(Long operatorId, long requestId);

    /**
 * Promote a whole linear request chain of one collection into a single
 * regression case : one http.request step per member, in the
 * exact order the run-set preview draws (single source — what the user sees
 * is what gets promoted, no re-ordering anywhere).
 *
 * @param operatorId acting user (audit chain + script operator context)
 * @param collectionId source collection
 * @return created case coordinates + collection lineage echo
 * @throws com.sixbean.testpike.common.exception.ServiceException
 * 40401 collection missing; 40001 empty flow, an orchestration row
 * (precondition/loop) or a ws member — the 04 step vocabulary is
 * not isomorphic, so promotion fails closed in plain words naming
 * the member instead of pretending; 40904 a member already promoted
 * (message names the existing case number — idempotent answer)
 */
    FlowPromotionResultView promoteFlow(Long operatorId, long collectionId);
}
