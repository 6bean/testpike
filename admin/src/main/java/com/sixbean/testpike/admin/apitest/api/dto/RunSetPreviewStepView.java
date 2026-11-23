package com.sixbean.testpike.admin.apitest.api.dto;

import java.util.List;

/**
 * Admin mirror of core ApiRunSetPreviewStep (launch-preview card row,
 * . Field names lock the wire contract with the core internal
 * endpoint — drift silently drops values (RunSetBridgeTest shape assertion).
 */
public record RunSetPreviewStepView(long requestId, int seqNo, String name, String method,
                                    String urlPath, List<String> extracts,
                                    List<String> references, List<RunSetPreviewDepView> deps) {
}
