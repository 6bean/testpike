package com.sixbean.testpike.core.journey.domain.component;

import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Journey run aggregation at batch terminal state . Invoked in-process from
 * BatchFinalizeSupport via AfterCommitExecutor — the same single guard point every other
 * batch-terminal consumer uses, so no second MQ path exists. A batch that is not part of
 * any journey run costs one indexed lookup and returns (ordinary batches keep zero side
 * effects). Every write is a conditional update (status IN (0,1) / status = 1) so a
 * double-triggered finalize is idempotent, mirroring BatchFinalizeSupport's own guard.
 * Notifications are dispatched to the journey owner only; all failures are swallowed with
 * a warning and never turn the batch pipeline red.
 */
@Component
public class JourneyRunFinalizeSupport {

    private static final Logger log = LoggerFactory.getLogger(JourneyRunFinalizeSupport.class);

    
    private static final int BATCH_DONE = 6;
    private static final int BATCH_PARTIAL_FAILED = 7;

    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationService notificationService;

    public JourneyRunFinalizeSupport(NamedParameterJdbcTemplate jdbc,
                                     NotificationService notificationService) {
        this.jdbc = jdbc;
        this.notificationService = notificationService;
    }

    /** Called after a batch advanced to a terminal state inside this finalize pass. */
    public void onBatchFinalized(long batchId, int batchTargetStatus) {
        try {
            aggregate(batchId, batchTargetStatus);
        } catch (Exception e) {
            log.warn("Journey run aggregation skipped for batch {}: {}", batchId, e.getMessage());
        }
    }

    private void aggregate(long batchId, int batchTargetStatus) {
        List<SegmentRow> touched = jdbc.query(
                "SELECT rs.id, rs.run_id, rs.project_id, rs.seq_no, j.owner_user_id, j.name "
                        + "FROM tp_journey_run_segment rs "
                        + "JOIN tp_journey_run r ON r.id = rs.run_id "
                        + "JOIN tp_journey j ON j.id = r.journey_id "
                        + "WHERE rs.batch_id = :batch AND rs.status IN (0, 1)",
                new MapSqlParameterSource("batch", batchId),
                (rs, n) -> new SegmentRow(rs.getLong("id"), rs.getLong("run_id"),
                        rs.getLong("project_id"), rs.getInt("seq_no"),
                        rs.getLong("owner_user_id"), rs.getString("name")));
        if (touched.isEmpty()) {
            return;
        }
        int outcome = batchTargetStatus == BATCH_DONE ? SEG_SUCCEEDED : SEG_FAILED;
        jdbc.update("UPDATE tp_journey_run_segment SET status = :outcome, finished_at = now(), "
                        + "updated_at = now(), failure_code = :code, failure_message = :message "
                        + "WHERE id = :id AND status IN (0, 1)",
                new MapSqlParameterSource().addValue("outcome", outcome)
                        .addValue("code", outcome == SEG_FAILED ? "BATCH_FAILED" : null,
                                java.sql.Types.VARCHAR)
                        .addValue("message", outcome == SEG_FAILED
                                ? "Expanded batch " + batchId + " ended in terminal status "
                                        + batchTargetStatus + "; open the batch detail to "
                                        + "inspect the failing cases."
                                : null,
                                java.sql.Types.VARCHAR)
                        .addValue("id", touched.get(0).id()));
        if (outcome == SEG_FAILED) {
            SegmentRow segment = touched.get(0);
            notify(segment.ownerUserId(), segment.projectId(), "JOURNEY_SEGMENT_FAILED",
                    "Journey segment failed",
                    "Segment " + segment.seqNo() + " of journey '" + segment.journeyName()
                            + "' failed (batch " + batchId + "). Open the batch detail to "
                            + "inspect the failing cases.",
                    "JOURNEY_SEGMENT_FAILED:" + batchId + ":" + segment.ownerUserId());
        }
        Set<Long> runs = new LinkedHashSet<>();
        for (SegmentRow row : touched) {
            runs.add(row.runId());
        }
        for (Long runId : runs) {
            closeRunIfAllTerminal(runId);
        }
    }

    private void closeRunIfAllTerminal(long runId) {
        List<Integer> open = jdbc.query(
                "SELECT count(*) AS c FROM tp_journey_run_segment WHERE run_id = :run "
                        + "AND status IN (0, 1)",
                new MapSqlParameterSource("run", runId),
                (rs, n) -> rs.getInt("c"));
        if (open.isEmpty() || open.get(0) > 0) {
            return;
        }
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM tp_journey_run_segment WHERE run_id = :run AND status = 3",
                new MapSqlParameterSource("run", runId), Integer.class);
        int runStatus = failed != null && failed > 0 ? RUN_ANY_FAILED : RUN_ALL_SUCCEEDED;
        int advanced = jdbc.update("UPDATE tp_journey_run SET status = :status, "
                        + "finished_at = now(), updated_at = now() "
                        + "WHERE id = :id AND status = 1",
                new MapSqlParameterSource().addValue("status", runStatus).addValue("id", runId));
        if (advanced == 0) {
            return;
        }
        RunHeader header = jdbc.queryForObject(
                "SELECT j.owner_user_id, j.name FROM tp_journey_run r "
                        + "JOIN tp_journey j ON j.id = r.journey_id WHERE r.id = :id",
                new MapSqlParameterSource("id", runId),
                (rs, n) -> new RunHeader(rs.getLong(1), rs.getString(2)));
        notify(header.ownerUserId(), null, "JOURNEY_RUN_FINISHED",
                "Journey run finished",
                "Journey '" + header.journeyName() + "' run " + runId + " finished with status "
                        + (runStatus == RUN_ALL_SUCCEEDED ? "all segments succeeded"
                        : "some segments failed") + ". The journey owner signs the exit on "
                        + "the timeline page.",
                "JOURNEY_RUN_FINISHED:" + runId);
    }

    private void notify(long userId, Long projectId, String eventType, String title,
                        String content, String dedupKey) {
        try {
            notificationService.create(new NotificationCreateInput(userId, projectId,
                    eventType, title, content, dedupKey));
        } catch (Exception e) {
            log.warn("Journey notification {} skipped for user {}: {}", eventType, userId,
                    e.getMessage());
        }
    }

    private static final int SEG_SUCCEEDED = 2;
    private static final int SEG_FAILED = 3;
    private static final int RUN_ALL_SUCCEEDED = 2;
    private static final int RUN_ANY_FAILED = 3;

    /** One journey run-segment row touched by a batch terminal event. */
    public record SegmentRow(long id, long runId, long projectId, int seqNo,
                             long ownerUserId, String journeyName) {
    }

    /** Journey header needed to close a run and notify its owner. */
    public record RunHeader(long ownerUserId, String journeyName) {
    }
}
