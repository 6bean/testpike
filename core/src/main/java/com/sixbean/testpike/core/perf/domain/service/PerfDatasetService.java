package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.perf.api.dto.PerfDatasetPageView;

import java.util.List;
import java.util.Map;

/**
 * Perf CSV dataset ledger (tp_perf_dataset, data pool).
 * <p>The file bytes live in object storage under the project-routed
 * perf-dataset scene; this service owns validation at upload (RFC4180 parse +
 * identifier-safe columns + K8 row budget) and metadata in the DB, and always
 * streams previews back from the object so there is exactly one copy of the
 * payload. Row allocation for scenario execution is consumed downstream by
 * the engine templates (next segment) — this service stays engine-agnostic.
 */
public interface PerfDatasetService {

    /** Upload + validate one CSV file for a project; returns the new dataset id. */
    long upload(long projectId, String name, String fileName, byte[] content);

    /** Dataset view with first N preview rows streamed from storage (N = K8 preview key). */
    Map<String, Object> view(long projectId, long id);

    /** Datasets of one project (unbounded legacy door kept for internal callers). */
    List<Map<String, Object>> list(long projectId);

    /**
     * One paged window of the project's datasets (display-scale iron law). Null/blank
     * sort keeps id DESC; a whitelisted key orders in SQL with the id DESC tie-break;
     * off-list keys are refused fail-closed by name.
     */
    PerfDatasetPageView listPaged(long projectId, int page, int pageSize,
                                  String sortBy, String sortDir);

    /**
 * Paged dataset window under one program lens — same anchor contract
 * as the scenario door above: explicit project OR lens (never neither), lens
 * narrows to its projects ∩ the operator's visibility, unreachable lens = honest
 * empty page. Trusted internal callers pass operatorId 0 (no personal narrowing).
 */
    default PerfDatasetPageView listPaged(long operatorId, long programId, long projectId,
                                          int page, int pageSize, String sortBy, String sortDir) {
        return listPaged(projectId, page, pageSize, sortBy, sortDir);
    }

    /** Delete the ledger row and its object (object removal is best-effort quiet). */
    void delete(long projectId, long id);

    /**
 * Engine × row-allocation policy for the wizard's honest-disable UI
 * : the live K8 default engine, the live K8 default
 * strategy, and the supported-mode matrix from PerfDatasetAllocPolicy —
 * the same object the adapters' dispatch guards consult, so a disabled
 * option in the UI and a fail-closed refusal at dispatch can never drift.
 */
    Map<String, Object> allocPolicy();
}
