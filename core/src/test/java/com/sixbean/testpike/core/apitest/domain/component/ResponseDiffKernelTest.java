package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the JSON response diff kernel (W-c5, four-color model).
 *
 * <p>The kernel compares two parsed JSON trees and emits one entry per leaf
 * difference: ADDED (right only) / REMOVED (left only) / TYPE_CHANGED (node type
 * differs) / VALUE_CHANGED (same type, different value). It is the structural
 * half of the response-diff feature; the transport/format tri-state (JSON vs
 * text vs binary) is decided upstream by the read path, not here.
 *
 * <p>Contract pinned here: JSONPath-style keys ($ and $.a.b[0] forms), stable
 * lexicographic ordering, numeric equality across int/float literals, bounded
 * value summaries, and fail-fast on unparsable input.
 */
class ResponseDiffKernelTest {

    @Test
    void keyOnlyOnRightIsAdded() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": 1}", "{\"a\": 1, \"b\": 2}");

        assertEquals(1, diffs.size());
        assertEquals("$.b", diffs.get(0).path());
        assertEquals(ResponseDiffKind.ADDED, diffs.get(0).kind());
        assertEquals("number", diffs.get(0).rightType());
        assertEquals("2", diffs.get(0).rightSummary());
    }

    @Test
    void keyOnlyOnLeftIsRemoved() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": 1, \"b\": 2}", "{\"a\": 1}");

        assertEquals(1, diffs.size());
        assertEquals("$.b", diffs.get(0).path());
        assertEquals(ResponseDiffKind.REMOVED, diffs.get(0).kind());
        assertEquals("number", diffs.get(0).leftType());
    }

    @Test
    void scalarTypeChangeIsTypeChanged() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": \"1\"}", "{\"a\": 1}");

        assertEquals(1, diffs.size());
        assertEquals("$.a", diffs.get(0).path());
        assertEquals(ResponseDiffKind.TYPE_CHANGED, diffs.get(0).kind());
        assertEquals("string", diffs.get(0).leftType());
        assertEquals("number", diffs.get(0).rightType());
    }

    @Test
    void sameTypeScalarDifferenceIsValueChanged() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": 1}", "{\"a\": 2}");

        assertEquals(1, diffs.size());
        assertEquals(ResponseDiffKind.VALUE_CHANGED, diffs.get(0).kind());
        assertEquals("1", diffs.get(0).leftSummary());
        assertEquals("2", diffs.get(0).rightSummary());
    }

    @Test
    void identicalDocumentsProduceNoDiff() {
        assertTrue(ResponseDiffKernel.diff(
                "{\"a\": {\"b\": [1, 2]}}", "{\"a\": {\"b\": [1, 2]}}").isEmpty());
    }

    @Test
    void intAndFloatLiteralsOfSameValueAreEqual() {
        assertTrue(ResponseDiffKernel.diff("{\"a\": 1}", "{\"a\": 1.0}").isEmpty());
    }

    @Test
    void nestedObjectPathUsesDotNotation() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"data\": {\"items\": {\"id\": 1}}}",
                "{\"data\": {\"items\": {\"id\": 2}}}");

        assertEquals(1, diffs.size());
        assertEquals("$.data.items.id", diffs.get(0).path());
    }

    @Test
    void longerArrayOnRightAddsTrailingIndices() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": [1]}", "{\"a\": [1, 2]}");

        assertEquals(1, diffs.size());
        assertEquals("$.a[1]", diffs.get(0).path());
        assertEquals(ResponseDiffKind.ADDED, diffs.get(0).kind());
    }

    @Test
    void shorterArrayOnRightRemovesTrailingIndices() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": [1, 2]}", "{\"a\": [1]}");

        assertEquals(1, diffs.size());
        assertEquals("$.a[1]", diffs.get(0).path());
        assertEquals(ResponseDiffKind.REMOVED, diffs.get(0).kind());
    }

    @Test
    void arrayElementFieldChangeUsesIndexedPath() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": [{\"x\": 1}, {\"y\": 2}]}",
                "{\"a\": [{\"x\": 9}, {\"y\": 2}]}");

        assertEquals(1, diffs.size());
        assertEquals("$.a[0].x", diffs.get(0).path());
        assertEquals(ResponseDiffKind.VALUE_CHANGED, diffs.get(0).kind());
    }

    @Test
    void entriesAreOrderedByPathForStableRendering() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"z\": 1, \"m\": {\"b\": 1, \"a\": 1}}",
                "{\"z\": 2, \"m\": {\"b\": 2, \"a\": 2}}");

        List<String> paths = diffs.stream().map(ResponseDiffEntry::path).toList();
        assertEquals(List.of("$.m.a", "$.m.b", "$.z"), paths);
    }

    @Test
    void containerSummariesAreTypeShapeOnlyNotFullPayload() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": {\"deep\": [1, 2, 3]}}", "{\"a\": null}");

        assertEquals(1, diffs.size());
        assertEquals(ResponseDiffKind.TYPE_CHANGED, diffs.get(0).kind());
        assertEquals("null", diffs.get(0).rightType());
        assertTrue(diffs.get(0).leftSummary().startsWith("object("));
        assertEquals("null", diffs.get(0).rightSummary());
    }

    @Test
    void longStringValuesAreSummarizedWithinBound() {
        String big = "x".repeat(500);
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": \"" + big + "\"}", "{\"a\": \"small\"}");

        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).leftSummary().length() <= 200,
                "scalar summary must stay bounded so the UI never renders megabytes");
    }

    @Test
    void unparsableJsonFailsFast() {
        assertThrows(ServiceException.class,
                () -> ResponseDiffKernel.diff("not json", "{\"a\": 1}"));
        assertThrows(ServiceException.class,
                () -> ResponseDiffKernel.diff("{\"a\": 1}", "   "));
    }

    @Test
    void rootScalarTypeChangeIsReportedAtRootPath() {
        List<ResponseDiffEntry> diffs = ResponseDiffKernel.diff(
                "{\"a\": 1}", "[1]");

        assertEquals(1, diffs.size());
        assertEquals("$", diffs.get(0).path());
        assertEquals(ResponseDiffKind.TYPE_CHANGED, diffs.get(0).kind());
    }
}
