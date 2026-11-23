package com.sixbean.testpike.common.aibatch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the dual-form batch normalization (structured items vs legacy plain texts).
 * <p>Guard: structured items win when both forms are sent; the legacy form keeps working for one
 * version cycle; an empty request stays empty so the core validation rejects it with the existing
 * human-readable message.
 */
class BatchItemNormalizerTest {

    @Test
    void structuredItemsWinWhenBothFormsArePresent() {
        List<AiBatchItem> items = List.of(new AiBatchItem("登录功能", 7L, null));
        List<AiBatchItem> normalized = BatchItemNormalizer.normalize(items, List.of("旧字段文本"));
        assertEquals(1, normalized.size());
        assertEquals(7L, normalized.get(0).requirementId());
    }

    @Test
    void legacyTextsBecomePlainItemsWithoutRequirementLink() {
        List<AiBatchItem> normalized = BatchItemNormalizer.normalize(null, List.of("A", "B"));
        assertEquals(2, normalized.size());
        assertEquals("A", normalized.get(0).text());
        assertNull(normalized.get(0).requirementId());
        assertNull(normalized.get(0).sourceAnchor());
    }

    @Test
    void bothFormsEmptyYieldsEmptyList() {
        assertTrue(BatchItemNormalizer.normalize(null, null).isEmpty());
        assertTrue(BatchItemNormalizer.normalize(List.of(), List.of()).isEmpty());
    }

    @Test
    void textsExtractionKeepsOrderAndNullSafety() {
        List<String> texts = BatchItemNormalizer.texts(
                List.of(new AiBatchItem("A", 1L, null), new AiBatchItem(null, 2L, null)));
        assertEquals(2, texts.size());
        assertEquals("A", texts.get(0));
        assertNull(texts.get(1));
    }
}
