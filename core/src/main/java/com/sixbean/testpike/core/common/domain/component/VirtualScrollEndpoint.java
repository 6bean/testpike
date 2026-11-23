package com.sixbean.testpike.core.common.domain.component;

/**
 * Virtual scrolling whitelist endpoints are the explicit exception list for the enforced pagination limit ruling.
 * <p> For normal interfaces, size must not exceed 200, and requests exceeding the limit are rejected with 40001; virtual scrolling lists fetch at most 1000 items in one request from the frontend.
 * Local rendering shards are a verified contract, and silent clamping is retained.
 * The whitelist is maintained as a single code enumeration, and new endpoints must be explicitly registered here without bypassing it through configuration.
 *
 */
public enum VirtualScrollEndpoint {

 /** The case list uses CaseListView with VIRTUAL_SIZE=1000 for a single request fetch. */
    CASES_LIST("GET /api/v1/cases"),

 /** The execution task list uses ExecutionCenterView with taskSize=1000 for virtual scrolling. */
    EXEC_TASKS_LIST("GET /api/v1/executors/tasks");

    private final String endpoint;

    VirtualScrollEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /** Contract endpoint identifiers are the corresponding paths in the interface design, used for traceability. */
    public String endpoint() {
        return endpoint;
    }
}
