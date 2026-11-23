package com.sixbean.testpike.admin.perf.infrastructure.remote.client;

import com.sixbean.testpike.admin.perf.api.dto.PerfDatasetPageView;

import java.util.Map;

/**
 * HTTP client contract for the core perf CSV dataset ledger doors
 * (admin→core layering iron law; mirrors {@code /internal/perf/datasets}).
 * <p>All validation and ownership checks stay single-sourced in the core
 * service; this bridge only forwards and unwraps (red line 6).
 */
public interface CorePerfDatasetClient {

    /** Upload one CSV file as a dataset of the project; returns the new id. */
    long upload(long projectId, String name, String fileName, byte[] content);

    /** Dataset detail including the first N preview rows from object storage. */
    Map<String, Object> view(long projectId, long id);

    /** Paged dataset window of one project (clamp/order/total are core semantics). */
    PerfDatasetPageView listPaged(long projectId, int page, int pageSize,
                                  String sortBy, String sortDir, long programId);

    /** Delete the dataset row and its object. */
    void delete(long projectId, long id);

    /** Engine × row-allocation matrix for the wizard's honest-disable UI. */
    Map<String, Object> allocPolicy();
}
