package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DSL fingerprint of a pinned run snapshot .
 * <p>Why: scenarios get cloned when a load profile moves to a new environment; the
 * strict same-scenario gate then refuses curve comparison even though the pressed
 * DSL is byte-identical. The fingerprint answers "is the pinned DSL the same" with a
 * stable sha256 over ONLY the DSL-bearing snapshot keys (pressure model/config,
 * login strategy, steps, effective SLA) — identity keys (name, envId) and dataset
 * pinning stay out because they do not change what the curve means.
 * <p>Stability: JSON payloads are parsed and re-serialized with recursively sorted
 * map keys (object order normalized, array order preserved — step order matters),
 * so two scenarios created with equivalent DSL in any key order hash to the same
 * value. Unparseable payloads degrade to their raw text (fail toward "not equal",
 * never toward a false equality).
 */
@Component
public class PerfDslFingerprint {

    /** Snapshot keys whose pinned values define the pressed DSL. */
    private static final List<String> DSL_KEYS =
            List.of("pressureModel", "pressureConfig", "loginStrategy", "steps", "sla");

    private final ObjectMapper objectMapper;

    public PerfDslFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * sha256 hex (lowercase, 64 chars) over the canonical serialization of the
     * snapshot's DSL-bearing keys. null input values are kept as explicit nulls so
     * "absent" vs "present" is a fingerprint difference.
     */
    public String hashOf(Map<String, Object> snapshot) {
        Map<String, Object> canonical = new TreeMap<>();
        for (String key : DSL_KEYS) {
            canonical.put(key, normalize(snapshot.get(key)));
        }
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(canonical);
        } catch (Exception impossible) {
            // A TreeMap of parsed/plain values is always serializable; falling back
            // to the string view keeps the hash defined rather than throwing away a
            // launch over a digest computation.
            serialized = String.valueOf(canonical);
        }
        return sha256Hex(serialized);
    }

    /** Parse JSON-text payload values so key order normalizes; plain values pass through. */
    private Object normalize(Object raw) {
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return normalizeParsed(objectMapper.readValue(trimmed, Object.class));
                } catch (Exception unparseable) {
                    return text;
                }
            }
            return text;
        }
        if (raw instanceof Map<?, ?> map) {
            return normalizeParsed(map);
        }
        return raw;
    }

    /** Recursively sort map keys (arrays keep their order — step order is semantic). */
    private Object normalizeParsed(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalizeParsed(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(item -> out.add(normalizeParsed(item)));
            return out;
        }
        return value;
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception unavailable) {
            // SHA-256 is mandated by the JCA spec — unreachable in practice.
            throw new IllegalStateException("SHA-256 digest unavailable", unavailable);
        }
    }
}
