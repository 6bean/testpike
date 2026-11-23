package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Canonical JSON serialization: map keys are written in sorted order at
 * EVERY nesting depth, so byte-equal output depends only on content, never
 * on how the maps were built (Map.of iteration order is salted per JVM,
 * parse order follows the wire, LinkedHashMap follows insertion).
 *
 * <p>The face that needs it: any digest/hash taken over a serialized JSON
 * payload (bucket-result cache keys, reuse ledgers, fingerprint inputs)
 * rotates on every process restart while the payload is unchanged — rows
 * written by an earlier process then never match again. One shared piece for
 * the whole repository (R40): the second consumer references this class, no
 * copy of the sorting logic.
 *
 * <p>List order is preserved — sequence is content here. Number/POJO
 * formatting is delegated to the caller-supplied mapper so the canonical
 * face never silently changes the wire shape of a non-key dimension.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    /**
     * Serialize {@code value} with recursively key-sorted maps using a copy
     * of the caller's mapper (modules and other settings preserved). The
     * copy is per-call on purpose: caching it in a static map keyed by the
     * mapper instance would be an unbounded static accumulator (R57), while
     * ObjectMapper.copy() is cheap next to a provider call.
     */
    public static String write(ObjectMapper base, Object value) throws JsonProcessingException {
        return base.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .writeValueAsString(value);
    }
}
