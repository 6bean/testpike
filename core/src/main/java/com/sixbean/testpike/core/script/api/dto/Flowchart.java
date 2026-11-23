package com.sixbean.testpike.core.script.api.dto;


public record Flowchart(String mermaid, int stepCount, java.util.List<FlowchartNode> nodes) {
}
