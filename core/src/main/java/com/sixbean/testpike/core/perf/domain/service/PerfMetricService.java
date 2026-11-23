package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.core.perf.api.dto.PerfLiveCurveView;
import com.sixbean.testpike.core.perf.domain.component.PerfErrorClassCount;
import com.sixbean.testpike.core.perf.domain.component.PerfEventRow;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;

import java.util.List;
import java.util.Map;


public interface PerfMetricService {

    
    int recordFrames(long projectId, long runId, List<PerfMetricFrame> frames);

    
    List<Map<String, Object>> listFrames(long projectId, long runId);

    /**
 * Aggregate-caliber frame reader : only the un-split frames
 * (step_key='') — the curve, metric card, and SLA verdict consume this face so
 * per-step frames (B-4 step lines) never double-count the same requests.
 */
    List<Map<String, Object>> listAggregateFrames(long projectId, long runId);

    /**
 * Live-curve read face for the running page : seconds strictly after the
 * cursor, ascending; when the window holds more than the point ceiling it collapses
 * into ceil(n/ceiling)-second buckets (p95=bucket max so a spike never hides, rps=bucket
 * mean, error_rate=Σfail/Σreq when bucket counts exist else per-second mean fallback).
 * Ownership = same (run, project) filter as frames: foreign run answers honest empty.
 *
 * @param sinceOffset last second the caller saw (null = first load, whole series)
 * @param maxPointsOverride explicit ceiling for tests/admin preview; null = K8 key
 * perf.live-curve.max-points (hot-updatable, never hardcoded)
 */
    PerfLiveCurveView liveCurve(long projectId, long runId, Integer sinceOffset,
                               Integer maxPointsOverride);

    
    int recordEvents(long projectId, long runId, List<PerfEventRow> events);

    
    List<Map<String, Object>> listEvents(long projectId, long runId);

    /**
 * Deepest stored aggregate second for the run (step_key='' caliber, same
 * curve face the live page reads), or null when no frame landed yet. Feeds
 * the terminal data-loss coverage check without loading the whole
 * second series into memory.
 */
    Integer maxAggregateSecondOffset(long projectId, long runId);

    /**
 * Error-class TopN over the run : sums the fixed five-key error-class
 * counts of un-split frames (step_key='' — same requests already counted there,
 * step lines would double-count), descending by count. Classes with zero
 * collected occurrence do not appear (honest empty, never a fabricated wall).
 */
    List<PerfErrorClassCount> errorClassTopN(long projectId, long runId);

}
