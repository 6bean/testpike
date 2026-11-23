package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backward-compat contract of the optional gap declaration key
 * from the RegenerationFeedback discipline).
 * <p>Under test: absent key / empty array / non-array garbage / a malformed
 * draft all fold to "no gaps" with the payload bytes returned verbatim; a
 * declared entry is extracted AND stripped so the persisted action JSON keeps
 * the legacy shape; blank pairs drop out; runaway replies stay clamped (8
 * items, 120 chars) so the event ledger cannot be bloated by one model pass.
 */
class ResourceGapDeclarationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String draft(String tailFields) {
        return "{\"version\":\"2.0\",\"caseId\":\"7\",\"env\":\"dev\","
                + "\"steps\":[]" + tailFields + "}";
    }

    @Test
    void absentKeyKeepsBytesAndYieldsNoGaps() {
        String raw = draft(",\"note\":\"x\"");

        var out = ResourceGapDeclaration.extract(mapper, raw);

        assertTrue(out.gaps().isEmpty());
        assertEquals(raw, out.json(), "absent key = zero payload drift");
    }

    @Test
    void emptyArrayKeepsBytes() {
        String raw = draft(",\"missingResources\":[]");

        var out = ResourceGapDeclaration.extract(mapper, raw);

        assertTrue(out.gaps().isEmpty());
        assertEquals(raw, out.json());
    }

    @Test
    void declaredGapIsExtractedAndKeyStrippedBeforePersist() {
        String raw = draft(",\"missingResources\":[{\"category\":\"数据工厂\","
                + "\"intent\":\"批量造退款单\"}]");

        var out = ResourceGapDeclaration.extract(mapper, raw);

        assertEquals(1, out.gaps().size());
        assertEquals("数据工厂", out.gaps().get(0).category());
        assertEquals("批量造退款单", out.gaps().get(0).intent());
        assertFalse(out.json().contains("missingResources"),
                "the declaration never rides the persisted action JSON");
        assertFalse(out.json().contains("批量造退款单"),
                "stripping removes the whole subtree, not just the key name");
    }

    @Test
    void malformedDraftFoldsSoftlyNeverThrows() {
        var out = ResourceGapDeclaration.extract(mapper, "这不是 JSON");

        assertTrue(out.gaps().isEmpty());
        assertEquals("这不是 JSON", out.json(), "soft extraction keeps generation going");
    }

    @Test
    void nonArrayGarbageValueKeepsBytes() {
        String raw = draft(",\"missingResources\":\"oops\"");

        var out = ResourceGapDeclaration.extract(mapper, raw);

        assertTrue(out.gaps().isEmpty());
        assertEquals(raw, out.json());
    }

    @Test
    void blankPairsDropAndOversizedReplyStaysClamped() {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            items.append(i == 0 ? "{\"category\":\"\",\"intent\":\"\"}"
                    : "{\"category\":\"c" + i + "\",\"intent\":\"长意图".repeat(1)
                            + "档".repeat(150) + "\"}");
            if (i < 11) {
                items.append(',');
            }
        }
        String raw = draft(",\"missingResources\":[" + items + "]");

        var out = ResourceGapDeclaration.extract(mapper, raw);

        assertEquals(8, out.gaps().size(), "the event ledger stays bounded");
        assertTrue(out.gaps().get(0).intent().length() <= 121,
                "each field clamped to 120 chars plus the ellipsis");
        assertFalse(out.json().contains("missingResources"));
    }
}
