package com.sixbean.testpike.aiservice.tool.domain.component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wire-level tool name aliasing for function-calling requests (single source).
 *
 * <p>Strict OpenAI-compatible providers reject {@code tools[].function.name}
 * that does not match {@code ^[A-Za-z0-9_-]{1,64}$} with an HTTP 400 that
 * fails the whole request. The tool catalog is an outward MCP contract whose
 * names legitimately contain dots and CJK characters, so the catalog must not
 * be renamed; instead this facet re-issues a provider-legal wire name at the
 * request-assembly choke point only.
 *
 * <p>Rules:
 * <ul>
 *   <li>already-legal names pass through unchanged (no pointless churn);</li>
 *   <li>illegal characters map to '_' one-for-one; overlong results are cut to
 *       a readable head plus a digest tail so names sharing a long prefix stay
 *       distinct after truncation;</li>
 *   <li>an alias never steals the original name of another catalog row —
 *       collisions advance with a numeric suffix.</li>
 * </ul>
 * The response side stays safe because the assembly index keeps the original
 * name routable and adds the alias as a second key (see
 * {@link LlmFunctionCalling#assemblePlan}).
 */
final class FcWireNames {

    /** Provider-side pattern observed in the LiteLLM/deepseek 400 verdict. */
    private static final Pattern PROVIDER_LEGAL = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final Pattern ILLEGAL_CHAR = Pattern.compile("[^A-Za-z0-9_-]");

    private static final int MAX_LEN = 64;

    private FcWireNames() {
    }

    /** True when the catalog name can travel on the wire untouched. */
    static boolean legal(String name) {
        return name != null && PROVIDER_LEGAL.matcher(name).matches();
    }

    /**
     * Build the wire-name table for one assembly pass.
     *
     * @param originalNames catalog names in assembly order (tool_id asc —
     *                      the table is deterministic per assembly view)
     * @return original name to wire name (identity for legal names)
     */
    static Map<String, String> assign(Collection<String> originalNames) {
        Set<String> reserved = new HashSet<>(originalNames);
        Map<String, String> table = new LinkedHashMap<>();
        for (String raw : originalNames) {
            if (legal(raw)) {
                table.put(raw, raw);
                continue;
            }
            String base = base(raw);
            String candidate = base;
            for (int n = 2; reserved.contains(candidate); n++) {
                String suffix = "_" + n;
                candidate = base.substring(0, Math.min(base.length(), MAX_LEN - suffix.length()))
                        + suffix;
            }
            reserved.add(candidate);
            table.put(raw, candidate);
        }
        return table;
    }

    /** Charset substitution plus bounded length; the digest tail keeps
     *  prefix-sharing overlong names apart. */
    private static String base(String raw) {
        String substituted = ILLEGAL_CHAR.matcher(raw).replaceAll("_");
        if (substituted.isEmpty()) {
            substituted = "_tool";
        }
        if (substituted.length() <= MAX_LEN) {
            return substituted;
        }
        String digest = digest8(raw);
        return substituted.substring(0, MAX_LEN - digest.length() - 1) + "_" + digest;
    }

    private static String digest8(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
                if (hex.length() >= 8) {
                    break;
                }
            }
            return hex.substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec; unreachable in practice.
            throw new IllegalStateException(e);
        }
    }
}
