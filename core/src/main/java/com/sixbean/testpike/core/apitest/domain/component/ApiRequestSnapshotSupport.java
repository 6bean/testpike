package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;

import java.util.List;

/**
 * Request-definition snapshot freezer (apitest domain, single shape).
 * <p>Two doors freeze request rows into the same pinned JSON array: the
 * collection-run ledger at launch and the flow template at save-as
 * . One shape, one code path — a template snapshot can be fed
 * straight into the run-set plan grammar, and "what you saved as a template"
 * is byte-for-byte "what a run would have pinned" (red line 6: the second
 * consumer extracted the first one's logic instead of copying it).
 * <p>Emptiness policy: orchestration rows land only when configured (an empty
 * precondition/loop form keeps the snapshot without the key), and the
 * transport discriminator lands only off the http default — inert keys never
 * bloat the stored JSON, absent means "no opinion" to every consumer.
 */
public final class ApiRequestSnapshotSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiRequestSnapshotSupport() {
        // static freezer, no instances
    }

    /** Freeze request rows in the given order (caller owns the sequence). */
    public static String pin(List<ApiRequestInfo> requests) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ApiRequestInfo req : requests) {
            ObjectNode node = arr.addObject();
            node.put("requestId", req.id());
            node.put("name", req.name());
            node.put("method", req.method());
            node.put("urlPath", req.urlPath());
            node.set("headersJson", readJson(req.headersJson(), "[]"));
            node.put("bodyText", req.bodyText() == null ? "" : req.bodyText());
            node.set("authJson", readJson(req.authJson(), "{}"));
            node.set("assertionsJson", readJson(req.assertionsJson(), "[]"));
            // Orchestration snapshot : the launch preview and the
            // whole-set plan freeze the same precondition/loop rows the request
            // carries — empty forms keep the pinned shape without the keys.
            JsonNode precondition = readJson(req.preconditionJson(), "{}");
            if (!precondition.isEmpty()) {
                node.set("preconditionJson", precondition);
            }
            JsonNode loop = readJson(req.loopJson(), "{}");
            if (!loop.isEmpty()) {
                node.set("loopJson", loop);
            }
            // Transport discriminator : only a non-default (ws)
            // row gets the key, so historical http snapshots stay unchanged.
            if (req.protocol() != null && !req.protocol().isBlank() && !"http".equals(req.protocol())) {
                node.put("protocol", req.protocol());
            }
        }
        return arr.toString();
    }

    /** Read a stored-JSON text slot tolerantly (blank → fallback shape). */
    public static JsonNode readJson(String raw, String fallback) {
        try {
            return MAPPER.readTree(raw == null || raw.isBlank() ? fallback : raw);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** Shared mapper handle for snapshot consumers (parse-side of pin). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
