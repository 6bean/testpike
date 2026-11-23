package com.sixbean.testpike.core.script.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.util.NonUiActionContract;
import com.sixbean.testpike.core.script.api.dto.FlowchartNode;

import java.util.ArrayList;
import java.util.List;


final class FlowchartRender {

    
    final List<String> defs = new ArrayList<>();
    
    final List<String> edges = new ArrayList<>();
    
    final List<String> loopBacks = new ArrayList<>();
    
    int nodeSeq;
 
    boolean hasNonUiDashed;
    /** Canvas mapping registry (W5 APP UI Flow): action nodes in creation order = document
     *  pre-order of steps; rendering helper nodes never enter. Emitted via Flowchart.nodes. */
    final List<FlowchartNode> actionNodes = new ArrayList<>();
    /** 1-based action-step pre-order index (counts only action nodes, unlike nodeSeq). */
    int actionSeq;

    
    String node(String label, String action) {
        String id = "s" + (++nodeSeq);
        boolean dashed = NonUiActionContract.isNonUiAction(action);
        if (dashed) {
            hasNonUiDashed = true;
        }
        if (action != null) {
            // action != null = action node (plain step or if/loop container passing its own
            // action name) — registers the canvas mapping; helper nodes pass null stay unmapped
            actionNodes.add(new FlowchartNode(id, ++actionSeq));
        }
        defs.add(id + "[\"" + sanitize(label) + "\"]" + (dashed ? ":::nonui" : ""));
        return id;
    }

    
    void link(String from, String to, String label) {
        if (from == null) {
            return;
        }
        edges.add(from + (label == null ? " --> " : " -->|" + sanitize(label) + "| ") + to);
    }

    
    String steps(JsonNode steps, String prev, int depth) {
        String cur = prev;
        for (JsonNode step : steps) {
            cur = step(step, cur, depth);
        }
        return cur;
    }

    private String step(JsonNode step, String prev, int depth) {
        String action = step != null && step.isObject() ? step.path("action").asText("") : "";
        if ("if".equals(action)) {
            return renderIf(step, prev, depth);
        }
        if ("loop".equals(action)) {
            return renderLoop(step, prev, depth);
        }
        String id = node(FlowchartLabels.stepLabel(step), action);
        link(prev, id, null);
        return id;
    }

    
    private String renderIf(JsonNode step, String prev, int depth) {
        // "if" here = the container step itself (canvas mapping identity carrier, not a B11
        // non-UI action — dashed check stays false); helper nodes below keep action=null
        String decision = node("判定（" + step.path("condition").asText("") + "）", "if");
        link(prev, decision, null);
        String join = node("汇聚", null);
        branch("成立", step.path("steps"), decision, join, depth + 1);
        JsonNode elseSteps = step.get("elseSteps");
        if (elseSteps != null && elseSteps.isArray()) {
            branch("不成立", elseSteps, decision, join, depth + 1);
        } else {
            link(decision, join, null);
        }
        return join;
    }

    
    private void branch(String label, JsonNode steps, String decision, String join, int depth) {
        String entry = node(label, null);
        link(decision, entry, null);
        String exit = steps(steps, entry, depth);
        link(exit, join, null);
    }

    
    private String renderLoop(JsonNode step, String prev, int depth) {
        // "loop" here = the container step itself (canvas mapping identity carrier)
        String loop = node(FlowchartLabels.loopLabel(step), "loop");
        link(prev, loop, null);
        String bodyExit = steps(step.path("steps"), loop, depth + 1);
        if (bodyExit.equals(loop)) {
            loopBacks.add(loop + " -->|空循环体| " + loop);
        } else {
            loopBacks.add(bodyExit + " -->|重复| " + loop);
        }
        String loopEnd = node("循环结束", null);
        link(loop, loopEnd, "完成");
        return loopEnd;
    }

    
    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append('“');
                case '\'' -> sb.append('‘');
                case '[' -> sb.append('［');
                case ']' -> sb.append('］');
                case '{' -> sb.append('｛');
                case '}' -> sb.append('｝');
                case '|' -> sb.append('｜');
                case '\n', '\r' -> sb.append(' ');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
