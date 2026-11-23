package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.core.apitest.api.dto.ApiEffectiveHeader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Effective-defaults resolver — the single merge verdict behind both the
 * dispatch envelope and the editor preview ; red line 6: one
 * interpretation of request-vs-collection inheritance, zero second copy).
 * <p>Headers: request rows convert first (same name → the request row wins and
 * keeps its casing), collection defaults only fill keys nobody else carries.
 * Name comparison is case-insensitive (HTTP header semantics), so a collection
 * {@code X-Env} never shadows a request {@code x-env}.
 * <p>Auth replaces as a whole block: a request carrying its own auth decides
 * for itself, {@code {"type":"none"}} is an explicit opt-out that must never
 * fall back to the collection default; a request with no auth inherits the
 * collection block, which itself treats none/empty as "no default".
 * <p>The resolution also reports provenance (request / collection /
 * none-explicit / none) — the preview face renders that as source chips so
 * inheritance is never invisible.
 */
@Component
public class ApiEffectiveDefaultsResolver {

    /** Header row provenance values (wire literals consumed by the editor). */
    public static final String SOURCE_REQUEST = "request";
    public static final String SOURCE_COLLECTION = "collection";

    /** Auth block verdict values (wire literals consumed by the editor). */
    public static final String AUTH_SOURCE_REQUEST = "request";
    public static final String AUTH_SOURCE_COLLECTION = "collection";
    public static final String AUTH_SOURCE_NONE_EXPLICIT = "none-explicit";
    public static final String AUTH_SOURCE_NONE = "none";

    /**
     * Resolved auth with provenance: {@code block} is what goes on the wire
     * (empty object when nothing applies), {@code source} the four-valued verdict.
     */
    public record AuthResolution(JsonNode block, String source) {
    }

    private final ObjectMapper objectMapper;

    public ApiEffectiveDefaultsResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
 * Merge request + collection header rows into the envelope object form.
 * Byte-identical to the pre- assembler behaviour (legacy empty
 * collection keeps the old envelope untouched).
 */
    public JsonNode mergedHeadersObject(JsonNode requestStored, JsonNode collectionStored) {
        ObjectNode merged = headersToObject(requestStored);
        JsonNode defaults = headersToObject(collectionStored);
        Iterator<String> it = defaults.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (!containsKeyIgnoreCase(merged, name)) {
                merged.put(name, defaults.path(name).asText(""));
            }
        }
        return merged;
    }

    /**
     * Merged headers as labelled rows (preview face): every wire row plus the
     * face it came from. Order = request rows first, then collection-only rows,
     * matching {@link #mergedHeadersObject} field order.
     */
    public List<ApiEffectiveHeader> headerRows(JsonNode requestStored, JsonNode collectionStored) {
        ObjectNode requestObj = headersToObject(requestStored);
        JsonNode merged = mergedHeadersObject(requestStored, collectionStored);
        List<ApiEffectiveHeader> rows = new ArrayList<>();
        Iterator<String> it = merged.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            String source = containsKeyIgnoreCase(requestObj, name)
                    ? SOURCE_REQUEST : SOURCE_COLLECTION;
            rows.add(new ApiEffectiveHeader(name, merged.path(name).asText(""), source));
        }
        return rows;
    }

    /** Whole-block auth resolution with the four-valued provenance verdict. */
    public AuthResolution resolveAuth(JsonNode requestAuth, JsonNode collectionAuth) {
        if (requestAuth != null && !requestAuth.isNull() && !requestAuth.isEmpty()) {
            return AuthFormContract.isNone(requestAuth)
                    ? new AuthResolution(objectMapper.createObjectNode(), AUTH_SOURCE_NONE_EXPLICIT)
                    : new AuthResolution(requestAuth, AUTH_SOURCE_REQUEST);
        }
        if (collectionAuth == null || collectionAuth.isNull() || collectionAuth.isEmpty()
                || AuthFormContract.isNone(collectionAuth)) {
            return new AuthResolution(objectMapper.createObjectNode(), AUTH_SOURCE_NONE);
        }
        return new AuthResolution(collectionAuth, AUTH_SOURCE_COLLECTION);
    }

    /**
     * Lenient parse of a stored jsonb text column: null/blank/garbage = empty
     * object (rows are shape-validated at save time; the read faces must never
     * crash on legacy noise, matching the envelope assembler's posture).
     */
    public JsonNode parseStored(String raw) {
        try {
            return raw == null || raw.isBlank() ? objectMapper.createObjectNode()
                    : objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** Array form [{name,value}] → object form; object form passes through; garbage = empty. */
    private ObjectNode headersToObject(JsonNode stored) {
        if (stored.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            Iterator<String> it = stored.fieldNames();
            while (it.hasNext()) {
                String name = it.next();
                copy.set(name, stored.path(name));
            }
            return copy;
        }
        ObjectNode obj = objectMapper.createObjectNode();
        if (stored.isArray()) {
            for (JsonNode row : stored) {
                String name = row.path("name").asText("");
                if (!name.isBlank()) {
                    obj.put(name, row.path("value").asText(""));
                }
            }
        }
        return obj;
    }

    private static boolean containsKeyIgnoreCase(ObjectNode node, String name) {
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            if (it.next().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
