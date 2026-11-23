package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.perf.api.dto.PerfRunPageView;

import java.util.List;
import java.util.Map;


public interface PerfRunService {

    
    long launch(long projectId, long scenarioId, Long operatorId);

    /**
     * Launch with an optional environment override (GB-1 scenario × environment
     * decoupling): a non-null envId must belong to the project and decides the
     * safety-gate tier, the GA-2 environment lock, dispatch base-url resolution and
     * the effective envId pinned into the run snapshot; null falls back to the
     * scenario default unchanged.
     */
    long launch(long projectId, long scenarioId, Long envId, Long operatorId);

    
    void finish(long projectId, long runId, int status, String stopReason);

    /**
     * Mark a finished run (B-7 golden-run marking): mark ∈ GOLDEN / CANDIDATE /
     * VOID. Only final-state runs (2/3/4) are markable; promoting a GOLDEN
     * atomically demotes the previous golden of the same scenario to CANDIDATE
     * (one golden per scenario, DB partial unique index as last-resort guard).
     * NULL = unmarked is the legacy semantics of every pre-feature row.
     */
    void mark(long projectId, long runId, String mark, Long operatorId);

    
    Map<String, Object> view(long projectId, long runId);

    
    List<Map<String, Object>> listByScenario(long projectId, long scenarioId);

    /**
 * One paged window of a scenario's runs the run drawer
 * consumes this door; clamp and the truthful total are core semantics,
 * order stays the historical newest-first id DESC because a headerless
 * div list has no sortable columns, the exemption is recorded there).
 */
    PerfRunPageView listByScenarioPaged(long projectId, long scenarioId, int page, int pageSize);

    /**
 * One paged window of every run in the project segment ( GB-2 run
 * ledger): unlike the drawer window this door has no scenario key, so a
 * run whose scenario was deleted (A-13 keeps finished history with a NULL
 * link) stays listed instead of vanishing from the workbench. Sort runs
 * through the shared whitelisted-column gate (the ledger table has real
 * sortable headers, so no exemption here); rows carry the historical view
 * columns plus scenario_name resolved live-first with the pinned launch
 * snapshot as the honest fallback for decoupled runs. The optional status
 * and mark filters narrow the window server-side (blank = unfiltered; an
 * unknown mark matches nothing rather than silently widening) so the
 * ledger's filter band paginates against the narrowed total.
 */
    PerfRunPageView listProjectPaged(long projectId, int page, int pageSize,
                                     String sortBy, String sortDir,
                                     Integer status, String mark);
}
