package com.sixbean.testpike.core.requirement.api.dto;

import java.util.List;


public record TraceChain(TraceNode root, List<TraceNode> nodes, int count, boolean truncated) {

 
    public static final int MAX_NODES = 200;
}
