package com.sixbean.testpike.core.perf.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Live-curve read view : the incremental response shape of the running-page
 * poll, replacing the full-window frames dump that re-shipped every stored second per 2s.
 * <p>Window contract: frames carries only aggregate-caliber seconds strictly after the
 * requested cursor. When the window exceeds the point ceiling (K8 hot-updatable key
 * perf.live-curve.max-points) the server collapses seconds into buckets and answers
 * sampled=true with the bucket width — an honest annotation, never a silent thinning;
 * the report page keeps reading the unsampled full series (run-page downsample,
 * report-page full caliber per the design ruling).
 *
 * @param frames ascending second rows shaped like the frames face; when sampled,
 * second_offset is the bucket's last second, p95_ms the bucket max
 * (a latency spike never hides behind averaging), rps the bucket mean,
 * and error_rate the request-weighted sum when bucket counts exist
 * @param sampled true when seconds were collapsed into buckets
 * @param bucketSeconds seconds merged per returned point (1 = raw per-second series)
 * @param lastOffset deepest second in this window — the cursor the page sends back on
 * the next poll; null when the window is empty
 * @param collectedTotal seconds stored in the window before downsampling (lets the page
 * state honestly how many raw seconds the curve summarizes)
 * @param maxPoints point ceiling the server applied (K8 hot-updatable or the
 * explicit override) — the page resyncs when its appended raw
 * tail outgrows this screen budget
 */
public record PerfLiveCurveView(List<Map<String, Object>> frames, boolean sampled,
                                int bucketSeconds, Integer lastOffset, long collectedTotal,
                                int maxPoints) {
}
