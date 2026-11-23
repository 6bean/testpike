package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.mock.api.dto.MockHitInfo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hit-ledger row → save-ready stub draft the inspector's
 * "make this request a stub" one click).
 * <p>The ledger already stores exactly what the plane answered (masked before
 * publishing — core never re-masks and must not rewrite), so promoting one
 * observation is a straight re-shape of its two snapshots into the shared
 * response-builder document. Same honesty floor as the cassette strict plan
 * (shared with {@link CassetteDraftMapper}, red line 6):
 * <ul>
 * <li>query stripped from the path — a stub pinned to one captured query
 * string would miss the next real call;</li>
 * <li>masked sentinel values ({@code ***}) never ride into a stub as fake
 * credentials and content-length is dropped because the captured body may
 * have been size-gated;</li>
 * <li>a row without a response plane (transport failure, unanswered miss) is
 * refused — nothing was recorded to promote and inventing a body would be a
 * dishonest draft;</li>
 * <li>forwarded miss rows (matched=false but a real upstream answer) are
 * promotable — capturing what the upstream actually answered is the point.</li>
 * </ul>
 * Like every other draft channel, this is a proposal: it posts through the
 * normal stub save chain, so MockRuleValidator stays the single validation
 * point. Provenance = record + hit:{id}, one rung finer than the cassette's
 * recording:{id} — a promoted rule retraces to the exact traffic observation.
 */
public class HitDraftMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One ledger row → one draft, or null when the row carries nothing to
     * promote (no method/path captured, or no response plane recorded).
     */
    public MockStubDraft toDraft(MockHitInfo hit) {
        if (hit == null) {
            return null;
        }
        Map<String, Object> request = hit.requestSnapshot() == null
                ? Map.of() : hit.requestSnapshot();
        String method = text(request.get("method"));
        String path = text(request.get("path"));
        Integer status = statusOf(hit.responseSnapshot());
        if (method == null || path == null || status == null) {
            return null;
        }
        String barePath = stripQuery(path);
        String key = method.toUpperCase(Locale.ROOT) + " " + barePath;
        return new MockStubDraft(key,
                MockStubDraft.clampName("检视 " + key),
                method.toUpperCase(Locale.ROOT),
                "exact",
                barePath,
                "[]",
                responseDocument(hit.responseSnapshot()),
                "record",
                "hit:" + hit.id());
    }

    /** Path without the query facet — stub matching keys on the URL shape. */
    private String stripQuery(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    /** The recorded answer status, or null when the row has no response plane. */
    private Integer statusOf(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        Object raw = response.get("status");
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return raw == null ? null : Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Response builder document (status/headers/bodyMode/body) — the same
     * shape the contract-direct and cassette mappers emit, so one form-builder
     * contract serves every channel. Captured bodies stay static: the ledger
     * row IS the truth of what was answered.
     */
    private String responseDocument(Map<String, Object> response) {
        Map<String, Object> headers = new LinkedHashMap<>();
        Object rawHeaders = response.get("headers");
        if (rawHeaders instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = text(entry.getKey());
                String value = text(entry.getValue());
                if (name == null || value == null) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (CassetteDraftMapper.MASK_SENTINEL.equals(value)
                        || CassetteDraftMapper.DROP_CONTENT_LENGTH.equals(lower)) {
                    continue;
                }
                headers.put(lower, value);
            }
        }
        Object rawBody = response.get("body");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("status", statusOf(response));
        doc.put("headers", headers);
        doc.put("bodyMode", "static");
        doc.put("body", rawBody == null ? "" : String.valueOf(rawBody));
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("hit draft serialization failed", e);
        }
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
