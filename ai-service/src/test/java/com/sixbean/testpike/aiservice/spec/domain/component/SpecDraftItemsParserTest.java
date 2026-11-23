package com.sixbean.testpike.aiservice.spec.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * residual-1 fixtures: the parser must tell the two shapes apart. A
 * legitimate {@code {"items":[]}} answer — the extraction contract explicitly
 * allows it for a chunk that carries no interface definition — parses to an
 * empty list so one interface-free chunk can no longer fail the whole run
 * into retries and the dead-letter queue. Malformed shapes (items key
 * missing, items not an array, invalid JSON) stay a hard
 * IllegalArgumentException, and the document-level anti-overwrite guard (all
 * chunks empty = human-readable refusal, never a write-back) lives in
 * SpecDraftExtractServiceImpl.
 */
class SpecDraftItemsParserTest {

    private final SpecDraftItemsParser parser = new SpecDraftItemsParser(new ObjectMapper());

    @Test
    void legitimateEmptyItemListParsesToEmptyListWithoutThrowing() {
        List<Map<String, Object>> items = parser.parse("{\"items\":[]}").items();
        assertTrue(items.isEmpty(), "interface-free chunk = skip-and-count-zero");
    }

    @Test
    void fencedLegitimateEmptyItemListAlsoParsesToEmptyList() {
        List<Map<String, Object>> items = parser.parse("```json\n{\"items\":[]}\n```").items();
        assertTrue(items.isEmpty(), "fenced empty answer keeps the same skip semantics");
    }

    @Test
    void missingItemsKeyIsStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"data\":[]}"),
                "absent items key = malformed output, not a legitimate empty chunk");
    }

    @Test
    void nonArrayItemsIsStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("{\"items\":{\"path\":\"/a\"}}"),
                "items must be an array; any other shape is malformed");
    }

    @Test
    void invalidJsonIsStillRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("not json at all"));
    }

    /** R52 root-cause pair (dead letters 28746/28747): the LLM occasionally
     *  returns one entry without a path inside an otherwise healthy array.
     *  The path contract stays enforced (such an entry never becomes a
     *  draft), but one malformed entry must not kill the whole document
     *  run into retries — it is dropped and counted for honest reporting
     *  (same resilience principle as the interface-free-chunk ruling above,
     *  lifted from chunk granularity to entry granularity). */
    @Test
    void entryMissingPathIsDroppedAndCountedWithoutFailingTheRun() {
        SpecDraftParseOutcome outcome = parser.parse(
                "{\"items\":[{\"method\":\"GET\",\"path\":\"/api/ping\"},"
                        + "{\"method\":\"POST\",\"title\":\"missing-path\"}]}");
        assertEquals(1, outcome.items().size(), "valid entry survives");
        assertEquals("/api/ping", outcome.items().get(0).get("path"));
        assertEquals(1, outcome.rejectedCount(), "pathless entry dropped, not thrown");
    }

    /** All entries malformed = empty items with the rejected count booked;
     *  the human-readable refusal for a fully unusable answer lives in
     *  SpecDraftExtractServiceImpl (document-level guard), the parser never
     *  throws on this shape. */
    @Test
    void allEntriesMissingPathYieldEmptyItemsWithRejectedCount() {
        SpecDraftParseOutcome outcome = parser.parse(
                "{\"items\":[{\"method\":\"GET\",\"title\":\"a\"},"
                        + "{\"method\":\"POST\",\"title\":\"b\"}]}");
        assertTrue(outcome.items().isEmpty());
        assertEquals(2, outcome.rejectedCount());
    }

    @Test
    void normalItemsStillParseWithTheKnownDraftFields() {
        List<Map<String, Object>> items = parser.parse(
                "{\"items\":[{\"method\":\"GET\",\"path\":\"/api/ping\",\"title\":\"ping\"}]}").items();
        assertEquals(1, items.size());
        assertEquals("GET", items.get(0).get("method"));
        assertEquals("/api/ping", items.get(0).get("path"));
        assertEquals("ping", items.get(0).get("title"));
    }
}
