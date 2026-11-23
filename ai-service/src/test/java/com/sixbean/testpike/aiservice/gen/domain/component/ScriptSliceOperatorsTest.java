package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 for the pure step-slicing / merging operators .
 * Invariants under test: order preserved, no overlap, no loss, last group may
 * be short, width 0 (or >= size) keeps the legacy whole-script shape, and the
 * merge of per-group DSL step arrays always yields valid JSON.
 */
class ScriptSliceOperatorsTest {

    private static List<String> steps(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "step-" + i).toList();
    }

    @Test
    void splitKeepsOrderNoOverlapAndLastGroupMayBeShort() {
        List<String> all = steps(25);
        List<List<String>> groups = ScriptSlicePlanner.plan(all, 10);

        assertEquals(3, groups.size());
        assertEquals(10, groups.get(0).size());
        assertEquals(10, groups.get(1).size());
        assertEquals(5, groups.get(2).size());
        List<String> flat = new ArrayList<>();
        groups.forEach(flat::addAll);
        assertEquals(all, flat, "concatenated groups must equal the original sequence");
    }

    @Test
    void zeroWidthMeansWholeRunSingleGroup() {
        List<String> all = steps(25);
        List<List<String>> groups = ScriptSlicePlanner.plan(all, 0);

        assertEquals(1, groups.size(), "width 0 = slicing off, the legacy single pass");
        assertEquals(all, groups.get(0));
    }

    @Test
    void widthNotSmallerThanSizeYieldsSingleGroup() {
        List<String> all = steps(8);
        assertEquals(1, ScriptSlicePlanner.plan(all, 10).size());
        assertEquals(1, ScriptSlicePlanner.plan(all, 8).size());
    }

    @Test
    void emptyStepsYieldNoGroups() {
        assertEquals(List.of(), ScriptSlicePlanner.plan(List.of(), 10));
    }

    @Test
    void negativeWidthFallsBackToWholeRun() {
        List<String> all = steps(4);
        assertEquals(1, ScriptSlicePlanner.plan(all, -7).size());
        assertEquals(all, ScriptSlicePlanner.plan(all, -7).get(0));
    }

    @Test
    void mergeConcatenatesGroupStepArraysInOrder() throws Exception {
        String groupA = "{\"steps\":[{\"stepNo\":1,\"action\":\"openUrl\",\"url\":\"a\"},"
                + "{\"stepNo\":2,\"action\":\"tap\"}]}";
        String groupB = "{\"steps\":[{\"stepNo\":3,\"action\":\"assertText\",\"text\":\"ok\"}]}";

        JsonNode merged = new ObjectMapper()
                .readTree(ScriptSliceMerger.merge(List.of(groupA, groupB)));

        assertTrue(merged.isObject(), "merged script must be a JSON object");
        JsonNode stepsNode = merged.path("steps");
        assertTrue(stepsNode.isArray(), "merged script must carry the steps array");
        assertEquals(3, stepsNode.size());
        assertEquals(1, stepsNode.get(0).path("stepNo").asInt());
        assertEquals("assertText", stepsNode.get(2).path("action").asText());
    }

    @Test
    void mergeAcceptsFencedModelOutputPerGroup() throws Exception {
        String fenced = "```json\n{\"steps\":[{\"stepNo\":1,\"action\":\"tap\"}]}\n```";
        String plain = "{\"steps\":[{\"stepNo\":2,\"action\":\"input\"}]}";

        JsonNode merged = new ObjectMapper()
                .readTree(ScriptSliceMerger.merge(List.of(fenced, plain)));

        assertEquals(2, merged.path("steps").size());
    }

    @Test
    void singleGroupMergeKeepsStepCount() throws Exception {
        String group = "{\"steps\":[{\"stepNo\":1,\"action\":\"tap\"},"
                + "{\"stepNo\":2,\"action\":\"scroll\"}]}";

        JsonNode merged = new ObjectMapper().readTree(ScriptSliceMerger.merge(List.of(group)));

        assertEquals(2, merged.path("steps").size());
    }

    @Test
    void mergeRejectsUnparseableGroupAndNamesItsOrdinal() {
        Exception error = assertThrows(Exception.class,
                () -> ScriptSliceMerger.merge(List.of("{\"steps\":[]}", "{\"steps\":")));

        assertTrue(error.getMessage().contains("第 2 组"),
                "failure message must name the offending group ordinal: " + error.getMessage());
    }

    @Test
    void mergeRejectsGroupWithoutStepsArray() {
        Exception error = assertThrows(Exception.class,
                () -> ScriptSliceMerger.merge(List.of("{\"foo\":1}")));

        assertTrue(error.getMessage().contains("第 1 组"),
                "failure message must name the offending group ordinal: " + error.getMessage());
    }
}
