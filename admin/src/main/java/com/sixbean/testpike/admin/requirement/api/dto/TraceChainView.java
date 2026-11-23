package com.sixbean.testpike.admin.requirement.api.dto;

import java.util.List;


public record TraceChainView(TraceNodeView root, List<TraceNodeView> nodes,
                             int count, boolean truncated) {
}
