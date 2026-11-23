package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cassette frame → save-ready stub draft mapping for playback
 * .
 * <p>Two replay plans over one sealed (or open) cassette:
 * <ul>
 * <li>{@link #strictDrafts} — one exact-path rule per distinct recorded URL
 * (query stripped — a stub pinned to one captured query string would miss the
 * next real call); newest answered frame represents the URL so late fixes on
 * the upstream carry into the replay;</li>
 * <li>{@link #fuzzyDrafts} — one template rule per endpoint group, bucketed
 * through the shared {@link RecordingPathNormalizer} (the same key the live
 * summary cluster uses — no second bucketing copy, red line 6). Id segments
 * that collapsed to {@code :id} are re-templated into {@code {id}} /
 * {@code {id1}}/{@code {id2}} captures the mock-server kernel understands.
 * Representative = newest 2xx so an accidental 500 during capture does not
 * become the mock's "normal answer"; a group of pure failures honestly keeps
 * its newest failure — replaying a flaky endpoint AS its failure is a valid
 * test asset.</li>
 * </ul>
 * <p>Honesty floor on every draft: frames without an upstream answer
 * (transport failure) are skipped — nothing was recorded to promote; masked
 * header values ({@code ***}) are dropped so the desensitizer's placeholder
 * never rides into a stub as a fake credential; {@code content-length} is
 * dropped because the captured body may have been truncated by the size gate
 * and a self-contradicting header breaks real clients.
 * <p>Like StubDraftMapper (contract channel-A), a draft is a proposal: it
 * posts through the normal stub save chain, so MockRuleValidator remains the
 * single validation point. Provenance = {@code record} + recording id so the
 * badge chain can retrace every promoted rule to its cassette.
 */
public class CassetteDraftMapper {

    /** Desensitizer placeholder (MockingGate writes it for sensitive values). */
    static final String MASK_SENTINEL = "***";

    /** Header that can contradict a size-gated body — never promoted. */
    static final String DROP_CONTENT_LENGTH = "content-length";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Strict replay plan: one exact draft per distinct recorded URL, newest
     * answered frame wins. Frames without an upstream answer are skipped.
     */
    public List<MockStubDraft> strictDrafts(long recordingId,
                                            List<MockRecordingFrameMessage> frames) {
        Map<String, MockRecordingFrameMessage> newestByUrl = new LinkedHashMap<>();
        for (MockRecordingFrameMessage frame : safe(frames)) {
            MockRecordingFrameMessage answered = usable(frame);
            if (answered == null) {
                continue;
            }
            String urlKey = answered.method() + " " + barePath(answered.path());
            newestByUrl.merge(urlKey, answered,
                    (older, newer) -> newer.occurredAt() >= older.occurredAt() ? newer : older);
        }
        List<MockStubDraft> out = new ArrayList<>();
        for (Map.Entry<String, MockRecordingFrameMessage> e : newestByUrl.entrySet()) {
            MockRecordingFrameMessage frame = e.getValue();
            out.add(toDraft(recordingId, frame, e.getKey(), "exact", barePath(frame.path())));
        }
        return out;
    }

    /**
     * Fuzzy replay plan: one draft per endpoint group (shared :id collapse).
     * Paths holding ids become template rules; plain paths stay exact — the
     * group stays honest to what was actually recorded.
     */
    public List<MockStubDraft> fuzzyDrafts(long recordingId,
                                           List<MockRecordingFrameMessage> frames) {
        Map<String, MockRecordingFrameMessage> representative = new LinkedHashMap<>();
        Map<String, MockRecordingFrameMessage> newestAny = new LinkedHashMap<>();
        for (MockRecordingFrameMessage frame : safe(frames)) {
            MockRecordingFrameMessage answered = usable(frame);
            if (answered == null) {
                continue;
            }
            String groupKey = answered.method() + " "
                    + RecordingPathNormalizer.normalize(barePath(answered.path()));
            newestAny.merge(groupKey, answered,
                    (older, newer) -> newer.occurredAt() >= older.occurredAt() ? newer : older);
            if (isSuccess(answered)) {
                representative.merge(groupKey, answered,
                        (older, newer) -> newer.occurredAt() >= older.occurredAt() ? newer : older);
            }
        }
        List<MockStubDraft> out = new ArrayList<>();
        for (Map.Entry<String, MockRecordingFrameMessage> e : newestAny.entrySet()) {
            MockRecordingFrameMessage pick = representative.getOrDefault(e.getKey(), e.getValue());
            String normalized = e.getKey().substring(e.getKey().indexOf(' ') + 1);
            String templated = reTemplate(normalized);
            String matchType = templated.indexOf('{') >= 0 ? "template" : "exact";
            MockStubDraft base = toDraft(recordingId, pick, e.getKey(), matchType, templated);
            out.add(new MockStubDraft(e.getKey(), base.name(), base.method(), matchType,
                    templated, base.matchersJson(), base.responseJson(),
                    base.source(), base.sourceRef()));
        }
        return out;
    }

    /** The frame only if it carries a real upstream answer (status present). */
    private MockRecordingFrameMessage usable(MockRecordingFrameMessage frame) {
        if (frame == null || frame.method() == null || frame.path() == null
                || frame.responseStatus() == null) {
            return null;
        }
        return frame;
    }

    /** Path without the query facet — stub matching keys on the URL shape. */
    private String barePath(String path) {
        int q = path.indexOf('?');
        return q < 0 ? path : path.substring(0, q);
    }

    private boolean isSuccess(MockRecordingFrameMessage frame) {
        int status = frame.responseStatus();
        return status >= 200 && status < 300;
    }

    private List<MockRecordingFrameMessage> safe(List<MockRecordingFrameMessage> frames) {
        return frames == null ? List.of() : frames;
    }

    private MockStubDraft toDraft(long recordingId, MockRecordingFrameMessage frame,
                                  String key, String matchType, String pathPattern) {
        // User-visible rule name: Chinese prefix per UI-first wording (same
        // plane as the shared clamp fallback — data value, not a code comment).
        return new MockStubDraft(key, MockStubDraft.clampName("回放 " + key),
                frame.method().toUpperCase(Locale.ROOT), matchType, pathPattern, "[]",
                responseDocument(frame), "record", "recording:" + recordingId);
    }

    /**
     * Response builder document (status/headers/bodyMode/body) — same shape
     * the contract-direct mapper emits, so one form-builder contract serves
     * both channels. Captured bodies stay static: the cassette IS the truth
     * of what the upstream answered, no template invention.
     */
    private String responseDocument(MockRecordingFrameMessage frame) {
        Map<String, Object> headers = new LinkedHashMap<>();
        if (frame.responseHeaders() != null) {
            for (Map.Entry<String, String> h : frame.responseHeaders().entrySet()) {
                String name = h.getKey() == null ? "" : h.getKey().toLowerCase(Locale.ROOT);
                if (name.isEmpty() || MASK_SENTINEL.equals(h.getValue())
                        || DROP_CONTENT_LENGTH.equals(name)) {
                    continue;
                }
                headers.put(name, h.getValue());
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("status", frame.responseStatus());
        doc.put("headers", headers);
        doc.put("bodyMode", "static");
        doc.put("body", frame.responseBody() == null ? "" : frame.responseBody());
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("cassette response document serialization failed", e);
        }
    }

    /**
     * Turn collapsed {@code :id} segments back into kernel template captures.
     * One id in the path → {@code {id}}; several → {@code {id1}}, {@code {id2}}
     * in order (distinct capture names the template engine can echo).
     */
    static String reTemplate(String normalizedPath) {
        String[] segments = normalizedPath.split("/");
        int ids = 0;
        for (String s : segments) {
            if (RecordingPathNormalizer.ID_SEGMENT.equals(s)) {
                ids++;
            }
        }
        StringBuilder out = new StringBuilder();
        int seen = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            out.append('/');
            if (RecordingPathNormalizer.ID_SEGMENT.equals(segment)) {
                seen++;
                out.append('{').append(ids == 1 ? "id" : "id" + seen).append('}');
            } else {
                out.append(segment);
            }
        }
        return out.length() == 0 ? "/" : out.toString();
    }
}
