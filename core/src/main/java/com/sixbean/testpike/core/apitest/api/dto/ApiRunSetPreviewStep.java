package com.sixbean.testpike.core.apitest.api.dto;

import java.util.List;

/**
 * One row of the launch-preview card for a collection run .
 * <p>The frontend draws the order and dependency arrows straight from this
 * shape — it never re-parses {{VAR:...}} itself (single source: the core plan
 * component computes extracts/edges, the page only renders).
 *
 * @param seqNo 1-based tree order (same order the run will dispatch in)
 * @param extracts pool keys this step writes for later hops
 * @param references every {{VAR:name}} key consumed by this step, including
 * keys produced outside the collection (honest raw view)
 * @param deps arrows to draw: consumed keys with an in-collection producer
 */
public record ApiRunSetPreviewStep(long requestId, int seqNo, String name, String method,
                                   String urlPath, List<String> extracts,
                                   List<String> references, List<ApiRunSetPreviewDep> deps) {
}
