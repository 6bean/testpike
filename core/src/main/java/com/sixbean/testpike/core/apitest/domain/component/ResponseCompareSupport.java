package com.sixbean.testpike.core.apitest.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Format tri-state routing and line-level text diff for the run-history
 * response compare (W-c5). The kernel owns JSON semantics; this support owns
 * the "which comparator applies" decision plus the honest fallback for bodies
 * that are not JSON:
 * <ul>
 *   <li>a body carrying U+0000 is binary — the caller reports SHA-256 digests
 *       only and never renders bytes;</li>
 *   <li>both bodies parsable as JSON → four-color {@link ResponseDiffKernel};</li>
 *   <li>anything else → line diff with common leading/trailing lines trimmed
 *       so a two-line change in a large HTML body stays readable.</li>
 * </ul>
 */
public final class ResponseCompareSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponseCompareSupport() {
    }

    /** Decide which comparator the bodies fall under (never throws — detection is total). */
    public static ResponseCompareMode detectMode(String leftBody, String rightBody) {
        if (containsNul(leftBody) || containsNul(rightBody)) {
            return ResponseCompareMode.BINARY;
        }
        return isParsableJson(leftBody) && isParsableJson(rightBody)
                ? ResponseCompareMode.JSON
                : ResponseCompareMode.TEXT;
    }

    /**
     * Line-level text diff. Aligned lines inside the trimmed window compare by
     * equality (VALUE_CHANGED), surplus lines on either side are REMOVED/ADDED.
     * Paths are 0-based line indexes in the original bodies ({@code line[N]})
     * so the UI can scroll to the exact line.
     */
    public static List<ResponseDiffEntry> textLineDiff(String leftBody, String rightBody) {
        String[] left = leftBody.split("\n", -1);
        String[] right = rightBody.split("\n", -1);
        int head = 0;
        while (head < left.length && head < right.length && left[head].equals(right[head])) {
            head++;
        }
        int tail = 0;
        while (tail < left.length - head && tail < right.length - head
                && left[left.length - 1 - tail].equals(right[right.length - 1 - tail])) {
            tail++;
        }
        int leftEnd = left.length - tail;
        int rightEnd = right.length - tail;
        int aligned = Math.min(leftEnd, rightEnd) - head;

        List<ResponseDiffEntry> out = new ArrayList<>();
        for (int i = 0; i < aligned; i++) {
            String l = left[head + i];
            String r = right[head + i];
            if (!l.equals(r)) {
                out.add(new ResponseDiffEntry("line[" + (head + i) + "]",
                        ResponseDiffKind.VALUE_CHANGED, "string", "string", l, r));
            }
        }
        for (int i = head + aligned; i < leftEnd; i++) {
            out.add(new ResponseDiffEntry("line[" + i + "]", ResponseDiffKind.REMOVED,
                    "string", "absent", left[i], null));
        }
        for (int j = head + aligned; j < rightEnd; j++) {
            out.add(new ResponseDiffEntry("line[" + j + "]", ResponseDiffKind.ADDED,
                    "absent", "string", null, right[j]));
        }
        return out;
    }

    /** U+0000 marker = binary payload (gzip/zip/pdf…); null-safe. */
    public static boolean containsNul(String body) {
        return body != null && body.indexOf('\u0000') >= 0;
    }

    private static boolean isParsableJson(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            return node != null && !node.isMissingNode();
        } catch (Exception e) {
            return false;
        }
    }
}
