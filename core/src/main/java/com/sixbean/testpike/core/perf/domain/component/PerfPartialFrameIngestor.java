package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Partial-frame heartbeat ingestion .
 * <p>perf_worker ships in-press seconds with heartbeats (rows = {runId, seq,
 * frames[]}, frame shape shared with the §2.4 result channel) so the live curve
 * updates at flush-window cadence instead of waiting for the final result. The
 * executor never names the project — the run row is the reverse-lookup anchor
 * (misdelivery guard: a stale or foreign runId can only land on its own project
 * segment, and a missing run row is dropped by name, never guessed onto one).
 * <p>Every ingestion rule stays at the single gate (PerfMetricService.recordFrames:
 * running-run guard, value ranges, second+step anchor replay idempotency — no
 * second rule set here). Same-value replays are idempotent; a different rps on a
 * taken anchor and late post-terminal frames are refused by that gate.
 * <p>Any refusal degrades to a named warning — the heartbeat main chain is never
 * dragged down (monitorFrames/components/netProxyAudits precedent, per-entry
 * isolation so one dirty row cannot sink the good rows of the same beat).
 */
@Component
public class PerfPartialFrameIngestor {

    private static final Logger log = LoggerFactory.getLogger(PerfPartialFrameIngestor.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final PerfMetricService perfMetricService;
    private final PerfWireFrameCodec codec;

    public PerfPartialFrameIngestor(NamedParameterJdbcTemplate jdbc,
                                    PerfMetricService perfMetricService,
                                    PerfWireFrameCodec codec) {
        this.jdbc = jdbc;
        this.perfMetricService = perfMetricService;
        this.codec = codec;
    }

    /** Heartbeat branch entry: per-row isolate + store; returns frames actually
     *  landed this beat (drop counts stay visible for caller-side logging). */
    public int ingest(List<?> rows) {
        int stored = 0;
        for (Object raw : rows) {
            stored += ingestOne(raw);
        }
        return stored;
    }

    private int ingestOne(Object raw) {
        if (!(raw instanceof Map<?, ?> row)) {
            log.warn("perf partial row is not an object ({}); skipped honestly",
                    raw == null ? "null" : raw.getClass().getSimpleName());
            return 0;
        }
        long runId = numberOrZero(row.get("runId"));
        if (runId <= 0) {
            log.warn("perf partial row without a valid runId (seq={}); dropped — "
                    + "the executor must anchor every flush window to a run", row.get("seq"));
            return 0;
        }
        Long projectId = lookupProjectId(runId);
        if (projectId == null) {
            // unknown/deleted run = misdelivery, dropped by name; guessing a project is forbidden
            log.warn("perf partial row points at run {} which does not exist; dropped "
                    + "(stale task or purged run — heartbeat main chain unaffected)", runId);
            return 0;
        }
        List<PerfMetricFrame> frames = codec.attachRowHostSample(
                codec.toFrames(row.get("frames")), row.get("hostStats"));
        if (frames.isEmpty()) {
            return 0;
        }
        try {
            return perfMetricService.recordFrames(projectId, runId, frames);
        } catch (ServiceException refused) {
            // terminal-late / replay-conflict / value-range: the single gate already
            // worded it in human language — keep it as a named warning, never rethrow
            log.warn("run {} partial frames refused by the ingestion gate ({}) — "
                    + "heartbeat main chain unaffected", runId, refused.getMessage());
            return 0;
        } catch (RuntimeException broken) {
            log.warn("run {} partial frames ingestion failed ({}) — degraded, next "
                    + "flush window ships fresh seconds anyway", runId, broken.getMessage());
            return 0;
        }
    }

    private Long lookupProjectId(long runId) {
        return jdbc.queryForList("SELECT project_id FROM tp_perf_run WHERE id = :r",
                        new MapSqlParameterSource("r", runId))
                .stream().findFirst()
                .map(r -> r.get("project_id") == null ? null
                        : ((Number) r.get("project_id")).longValue())
                .orElse(null);
    }

    private static long numberOrZero(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
