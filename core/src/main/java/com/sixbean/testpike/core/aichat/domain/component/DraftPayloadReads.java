package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Shared payload reads for every draft landing strategy (single source, R40).
 * <p>Each strategy used to carry its own copy of "parse the stored payload as
 * a JSON object" and "read an optional text field, blank means not stated".
 * The duplication gate flagged the family the moment a new strategy joined
 * it, so the two reads now live here once; strategies keep their own refusal
 * wording and error mapping on top of these deterministic reads.
 */
public final class DraftPayloadReads {

    private DraftPayloadReads() {
    }

    /**
     * Parses the stored draft payload and rejects anything that is not a
     * JSON object. The caller decides how to phrase the refusal — a parse
     * failure surfaces as an unchecked-adjacent exception here, never as a
     * silently half-read payload.
     */
    public static JsonNode requireObject(ObjectMapper mapper, String payloadJson)
            throws JsonProcessingException {
        JsonNode node = mapper.readTree(payloadJson);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return node;
    }

    /** Blank or missing optional text reads as "not stated" (null). */
    public static String textOrNull(JsonNode body, String field) {
        String raw = body.path(field).asText("").trim();
        return raw.isEmpty() ? null : raw;
    }

    /**
     * Raw optional text read: a missing or JSON-null field reads as null, but
     * an empty string stays empty (blank is a stated value here). Distinct
     * from textOrNull, which folds blank into "not stated".
     */
    public static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** Optional integer read: missing, null or non-number all read as null. */
    public static Integer integer(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() || !node.isNumber() ? null : node.asInt();
    }

    /**
     * Parse the stored payload and refuse with the caller's own wording when
     * it is unreadable. Strategies differ only in the refusal sentence; the
     * parse-and-map-to-refusal dance lives here once.
     */
    public static JsonNode parseOrRefuse(ObjectMapper mapper, String payloadJson, String refusal) {
        try {
            return requireObject(mapper, payloadJson);
        } catch (Exception e) {
            throw new com.sixbean.testpike.common.exception.ServiceException(
                    refusal, com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Read the draft row's own id. Every landing strategy keys its audit and
     * ticket bookkeeping off this one number, so the cast shape lives here
     * once instead of being re-typed per strategy (R40 — the duplication gate
     * flags the raw cast the moment two strategies carry it side by side).
     */
    public static long requireDraftId(Map<String, Object> draftRow) {
        return ((Number) draftRow.get("id")).longValue();
    }

}
