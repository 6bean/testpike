package com.sixbean.testpike.core.mock.domain.component;

import java.util.regex.Pattern;

/**
 * Shared endpoint-group normalization for recorded traffic ( segment
 * 3). The live cluster aggregate (tp_mock_recording.summary_json) and the
 * cassette replay planner must bucket the same way or a recorded flood of
 * ids would show one endpoint in the summary and explode into thousands of
 * fuzzy rules at replay time — single source, no second copy (red line 6).
 * <p>Path segments that are pure digits or UUIDs collapse to {@code :id}.
 */
public final class RecordingPathNormalizer {

    /** Placeholder a volatile id segment collapses to. */
    public static final String ID_SEGMENT = ":id";

    private static final Pattern UUID_SEGMENT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private RecordingPathNormalizer() {
    }

    /** True when the segment is an id (digits or UUID) rather than a resource name. */
    public static boolean isIdSegment(String segment) {
        return !segment.isEmpty()
                && (segment.chars().allMatch(Character::isDigit)
                    || UUID_SEGMENT.matcher(segment).matches());
    }

    /** Collapse id segments to :id ("/payments/123" → "/payments/:id"); empty path → "/". */
    public static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            out.append('/');
            out.append(isIdSegment(segment) ? ID_SEGMENT : segment);
        }
        return out.length() == 0 ? "/" : out.toString();
    }
}
