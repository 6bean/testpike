package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioPageView;

import java.util.List;
import java.util.Map;


public interface PerfScenarioService {

    
    long create(long projectId, PerfScenarioInput input);

    
    void update(long projectId, long id, PerfScenarioInput input);

    
    Map<String, Object> view(long projectId, long id);

    
    List<Map<String, Object>> list(long projectId);

    /**
 * One paged window of the project's scenarios . Null/blank
 * sort keeps the historical id DESC order; a whitelisted key orders in SQL
 * with the id DESC tie-break. Off-list or injection-shaped keys are refused
 * fail-closed by name; an unknown project answers a truthful empty window.
 */
    PerfScenarioPageView listPaged(long projectId, int page, int pageSize,
                                   String sortBy, String sortDir);

    /**
 * Paged scenario window under one program lens ( MP3b-4, fifth consumer of the
 * ProgramLensScope single source). Anchor rule mirrors the defect door: exactly one
 * scope anchor is required — an explicit project, or a program lens narrowing the
 * window to its projects ∩ the operator's visibility (conjunctive when both ride).
 * An unreachable lens answers an honest empty page, never 403 and never a leak.
 *
 * @param operatorId caller for the visibility intersection (trusted internal
 * callers pass 0 = no personal narrowing, self-filter convention).
 * @param programId lens id; 0 or negative = no lens.
 * @param projectId explicit project anchor; 0 = absent (lens-only listing).
 */
    default PerfScenarioPageView listPaged(long operatorId, long programId, long projectId,
                                           int page, int pageSize, String sortBy, String sortDir) {
        return listPaged(projectId, page, pageSize, sortBy, sortDir);
    }

    /**
     * Read-only impact ledger for the delete confirmation card (A-13): how many
     * runs are still in flight, how many finished runs the deletion would keep
     * (decoupled), and how many approval tickets are linked / still pending on
     * this scenario, plus the scenario source (DSL/CUSTOM) so the card can name
     * the mounted script when there is one. Unknown or foreign scenario answers
     * the same non-leaking 404 wording as the detail door.
     */
    Map<String, Object> deleteImpact(long projectId, long id);

    /**
     * Delete a scenario (A-13, D2 ruling): finished runs survive with a NULL
     * link — history reads the pinned launch snapshot, never the recipe row —
     * and linked approval tickets drop their scenario leg. A scenario with any
     * run still in flight is refused with a reason naming the blocker; the
     * CUSTOM script body in object storage retires with the row. Cross-project
     * or unknown id is refused without leaking existence.
     */
    void delete(long projectId, long id);
}
