package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.script.api.dto.Flowchart;

/**
 * <ul>
 * </ul>
 *
 */
public final class FlowchartMermaidAssembler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    
    private static final int MAX_NEST_DEPTH = 5;

    private FlowchartMermaidAssembler() {
    }

    
    public static Flowchart assemble(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return placeholder();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(actionJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("动作 JSON 不是合法 JSON，无法生成流程图", e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("动作 JSON 不是 JSON 对象，无法生成流程图");
        }
        JsonNode steps = root.get("steps");
        if (steps != null && !steps.isArray()) {
            throw new IllegalArgumentException("动作 JSON 的 steps 不是步骤数组，无法生成流程图");
        }
        if (steps == null || steps.isEmpty()) {
            return placeholder();
        }
        int stepCount = countActionNodes(steps, 1);
        FlowchartRender render = new FlowchartRender();
        render.steps(steps, null, 1);
        render.edges.addAll(render.loopBacks);
        StringBuilder mermaid = new StringBuilder("flowchart TD\n");
        for (String def : render.defs) {
            mermaid.append("    ").append(def).append('\n');
        }
        for (String edge : render.edges) {
            mermaid.append("    ").append(edge).append('\n');
        }
        if (render.hasNonUiDashed) {
            mermaid.append("    classDef nonui stroke-dasharray: 5 5\n");
        }
        return new Flowchart(mermaid.toString().stripTrailing(), stepCount, render.actionNodes);
    }

 
    private static Flowchart placeholder() {
        return new Flowchart(
                "flowchart TD\n    s1[\"暂无操作步骤，请先在脚本中维护动作步骤\"]", 0, java.util.List.of());
    }

    
    private static int countActionNodes(JsonNode steps, int depth) {
        int total = 0;
        for (JsonNode step : steps) {
            total += countActionStep(step, depth);
        }
        return total;
    }

    private static int countActionStep(JsonNode step, int depth) {
        if (!step.isObject()) {
            return 1;
        }
        String action = step.path("action").asText("");
        if ("if".equals(action)) {
            enterContainer(depth);
            int total = 1 + countActionNodes(step.path("steps"), depth + 1);
            JsonNode elseSteps = step.get("elseSteps");
            if (elseSteps != null && elseSteps.isArray()) {
                total += countActionNodes(elseSteps, depth + 1);
            }
            return total;
        }
        if ("loop".equals(action)) {
            enterContainer(depth);
            return 1 + countActionNodes(step.path("steps"), depth + 1);
        }
        return 1;
    }

    private static void enterContainer(int depth) {
        if (depth > MAX_NEST_DEPTH) {
            throw new IllegalArgumentException(
                    "动作步骤嵌套超过 " + MAX_NEST_DEPTH + " 层，流程图无法装配（深度上限可在「系统设置 > 参数配置」调整 ai.script.max-nest-depth）");
        }
    }
}
