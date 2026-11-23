package com.sixbean.testpike.admin.cases.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canvas node mapping bridge contract (W5 APP phase 2, UI Flow canvas view).
 * <p>The admin side is an isomorphic mirror of the core {@code Flowchart} payload — the canvas
 * consumers translate a clicked node id to the action step it projects through this mapping only,
 * so the mirror must carry it verbatim instead of letting the front end re-derive the traversal.
 * <p>Assertions: ① core JSON deserializes into the mirror with every (id, stepIndex) pair kept in
 * payload order; ② a payload without the mapping still deserializes (older producer tolerance,
 * mapping absent reads as absent rather than as an error); ③ the two-argument construction form
 * used by existing bridge stubs stays compilable and leaves the mapping unset.
 */
class FlowchartCanvasMappingBridgeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesCorePayloadCarryingNodeMapping() throws Exception {
        String coreJson = """
                {"mermaid":"flowchart TD\\n    s1[\\"导航首页\\"]\\n    s2[\\"登录\\"]",
                 "stepCount":2,
                 "nodes":[{"id":"s1","stepIndex":1},{"id":"s2","stepIndex":2}]}
                """;

        FlowchartView view = mapper.readValue(coreJson, FlowchartView.class);

        assertEquals("flowchart TD", view.mermaid().lines().findFirst().orElse("").trim());
        assertEquals(2, view.stepCount());
        List<FlowchartNodeView> nodes = view.nodes();
        assertEquals(2, nodes.size(), "canvas mapping must survive the bridge verbatim");
        assertEquals("s1", nodes.get(0).id());
        assertEquals(1, nodes.get(0).stepIndex());
        assertEquals("s2", nodes.get(1).id());
        assertEquals(2, nodes.get(1).stepIndex());
    }

    @Test
    void toleratesPayloadWithoutNodeMapping() throws Exception {
        FlowchartView view = mapper.readValue(
                "{\"mermaid\":\"flowchart TD\",\"stepCount\":0}", FlowchartView.class);

        assertEquals(0, view.stepCount());
        // Absence normalizes to an empty list rather than null: the core assembler always emits a
        // list (placeholder included), so consumers must never branch on a null mapping.
        assertTrue(view.nodes().isEmpty(), "absent mapping reads as no mapping, never null");
    }

    @Test
    void keepsTwoArgumentConstructionFormUsedByBridgeStubs() {
        FlowchartView view = new FlowchartView("flowchart TD", 0);

        assertTrue(view.nodes().isEmpty(), "two-argument form leaves the mapping explicitly empty");
    }
}
