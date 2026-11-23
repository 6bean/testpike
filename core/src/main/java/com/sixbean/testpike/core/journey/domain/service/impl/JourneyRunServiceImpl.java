package com.sixbean.testpike.core.journey.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.journey.domain.component.SegmentParamsMissingException;
import com.sixbean.testpike.core.journey.api.dto.JourneyRunInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneyRunSegmentInfo;
import com.sixbean.testpike.core.journey.domain.service.JourneyRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Journey run expansion and exit sign-off . Expansion walks plan segments in
 * seqNo order and launches one batch per segment inside its own transaction: a segment that
 * fails its pre-check (asset segment without a case list in params_override_json) or whose
 * batch launch throws is marked failed with a named failure_code, every downstream segment
 * is marked skipped and the run closes as failed — batches already launched stay queued and
 * keep running (honest partial launch, no rollback of sibling segments).
 * Sign-off authority is exactly one account: the journey owner recorded on tp_journey.
 */
@Service
public class JourneyRunServiceImpl implements JourneyRunService {

    private static final int RUN_RUNNING = 1;
    private static final int RUN_ALL_SUCCEEDED = 2;
    private static final int RUN_ANY_FAILED = 3;
    private static final int SEG_PENDING = 0;
    private static final int SEG_RUNNING = 1;
    private static final int SEG_SUCCEEDED = 2;
    private static final int SEG_FAILED = 3;
    private static final int SEG_SKIPPED = 4;

    private static final RowMapper<JourneyRunInfo> RUN_MAPPER = (rs, n) -> new JourneyRunInfo(
            rs.getLong("id"),
            rs.getLong("journey_id"),
            rs.getInt("status"),
            (Long) rs.getObject("signed_by"),
            rs.getObject("signed_at", OffsetDateTime.class),
            List.of());

    private static final RowMapper<JourneyRunSegmentInfo> SEG_MAPPER = (rs, n) ->
            new JourneyRunSegmentInfo(
                    rs.getLong("id"),
                    rs.getLong("segment_id"),
                    rs.getInt("seq_no"),
                    rs.getLong("project_id"),
                    (Long) rs.getObject("batch_id"),
                    rs.getInt("status"),
                    rs.getString("failure_code"),
                    rs.getString("failure_message"));

    private static final Logger log = LoggerFactory.getLogger(JourneyRunServiceImpl.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final BatchService batchService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    public JourneyRunServiceImpl(NamedParameterJdbcTemplate jdbc,
                                 BatchService batchService,
                                 TransactionTemplate transactionTemplate,
                                 ObjectMapper objectMapper,
                                 AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.batchService = batchService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
    }

    @Override
    public JourneyRunInfo startRun(long operatorId, long journeyId) {
        PlanSnapshot plan = readPlan(journeyId);
        if (plan.segments().isEmpty()) {
            throw new ServiceException("Journey " + journeyId + " has no segments; "
                    + "add at least one segment before starting a run");
        }
        List<JourneySegmentRow> remaining = createRunRows(plan);
        long runId = remaining.get(0).runId();
        for (int i = 0; i < remaining.size(); i++) {
            JourneySegmentRow segment = remaining.get(i);
            try {
                Long batchId = transactionTemplate.execute(
                        status -> launchSegment(operatorId, segment, runId, plan.journeyName()));
                markSegmentLaunched(segment.runSegmentId(), batchId);
            } catch (SegmentParamsMissingException e) {
                failRun(operatorId, runId, segment, "PARAMS_MISSING",
                        "Asset segment " + segment.seqNo() + " of project " + segment.projectId()
                                + " has no case list in params_override_json; provide caseIds "
                                + "(and optionally envId) on the segment before launching the run");
                skipDownstream(runId, remaining, i + 1);
                break;
            } catch (RuntimeException e) {
                log.warn("Journey run {} segment {} of project {} batch launch failed: {}",
                        runId, segment.seqNo(), segment.projectId(), e.getMessage());
                failRun(operatorId, runId, segment, "LAUNCH_FAILED",
                        "Batch launch failed: " + e.getMessage());
                skipDownstream(runId, remaining, i + 1);
                break;
            }
        }
        return getRun(runId);
    }

    @Override
    public JourneyRunInfo getRun(long runId) {
        JourneyRunInfo header = first(jdbc.query(
                "SELECT id, journey_id, status, signed_by, signed_at FROM tp_journey_run WHERE id = :id",
                new MapSqlParameterSource("id", runId), RUN_MAPPER),
                () -> new ServiceException("Journey run " + runId + " does not exist"));
        List<JourneyRunSegmentInfo> segments = jdbc.query(
                "SELECT id, segment_id, seq_no, project_id, batch_id, status, "
                        + "failure_code, failure_message FROM tp_journey_run_segment "
                        + "WHERE run_id = :run ORDER BY seq_no",
                new MapSqlParameterSource("run", runId), SEG_MAPPER);
        return new JourneyRunInfo(header.id(), header.journeyId(), header.status(),
                header.signedBy(), header.signedAt(), segments);
    }

    @Override
    public JourneyRunInfo signOff(long operatorId, long runId) {
        JourneyRunInfo run = getRun(runId);
        if (run.status() == RUN_RUNNING) {
            throw new ServiceException("Journey run " + runId + " is still running; "
                    + "exit sign-off only applies to a finished run");
        }
        if (run.signedBy() != null) {
            throw new ServiceException("Journey run " + runId + " is already signed at "
                    + run.signedAt() + " by user " + run.signedBy()
                    + "; exit sign-off happens exactly once per run");
        }
        // Exit authority is exactly the journey owner — tp_journey.owner_user_id,
        // re-checked service-side on every attempt (no second permission source).
        Long ownerUserId = jdbc.queryForObject(
                "SELECT j.owner_user_id FROM tp_journey_run r "
                        + "JOIN tp_journey j ON j.id = r.journey_id WHERE r.id = :id",
                new MapSqlParameterSource("id", runId), Long.class);
        if (ownerUserId == null || !ownerUserId.equals(operatorId)) {
            throw new ServiceException("Exit sign-off for journey run " + runId
                    + " belongs to the journey owner (user " + ownerUserId + "); caller "
                    + operatorId + " is not the owner and cannot sign", BizErrorCode.FORBIDDEN);
        }
        jdbc.update("UPDATE tp_journey_run SET signed_by = :signer, signed_at = now(), "
                        + "updated_at = now() WHERE id = :id AND signed_by IS NULL",
                new MapSqlParameterSource().addValue("signer", operatorId).addValue("id", runId));
        auditLogService.record(new AuditLogInput(operatorId, null, "journey_signoff",
                String.valueOf(runId), "JOURNEY_SIGNOFF", Map.of(),
                Map.of("signedBy", operatorId, "runStatus", run.status())));
        return getRun(runId);
    }

    /** Case-list/env resolution per segment; thrown inside its own transaction only. */
    private Long launchSegment(long operatorId, JourneySegmentRow segment, long runId,
                               String journeyName) {
        List<Long> caseIds;
        JsonNode params = readParams(segment.paramsOverrideJson());
        // Segment env travels in params_override_json.envId for both target kinds —
        // batch start refuses a launch without an execution environment.
        Long envId = segment.envId();
        JsonNode envNode = params.path("envId");
        if (envNode.isNumber()) {
            envId = envNode.asLong();
        }
        if ("case".equals(segment.targetKind())) {
            caseIds = List.of(segment.caseId());
        } else {
            JsonNode listed = params.path("caseIds");
            if (!listed.isArray() || listed.isEmpty()) {
                throw new SegmentParamsMissingException(
                        "asset segment without caseIds in params_override_json");
            }
            caseIds = new ArrayList<>();
            for (JsonNode node : listed) {
                caseIds.add(node.asLong());
            }
        }
        BatchInfo batch = batchService.create(operatorId, new BatchCreateInput(
                segment.projectId(),
                "journey-" + journeyName + "-run" + runId + "-seg" + segment.seqNo(),
                envId, caseIds, null));
        batchService.start(operatorId, batch.id());
        return batch.id();
    }

    private JsonNode readParams(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ServiceException("Segment params_override_json is not valid JSON: "
                    + e.getMessage());
        }
    }

    private PlanSnapshot readPlan(long journeyId) {
        List<long[]> header = jdbc.query(
                "SELECT id, owner_user_id FROM tp_journey WHERE id = :id AND status = 1",
                new MapSqlParameterSource("id", journeyId),
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
        if (header.isEmpty()) {
            throw new ServiceException("Journey " + journeyId
                    + " does not exist or is archived; a run needs an active journey plan");
        }
        String journeyName = jdbc.queryForObject(
                "SELECT name FROM tp_journey WHERE id = :id",
                new MapSqlParameterSource("id", journeyId), String.class);
        List<JourneySegmentRow> segments = jdbc.query(
                "SELECT s.id, s.seq_no, s.project_id, s.target_kind, s.asset_row_id, s.case_id, "
                        + "s.params_override_json "
                        + "FROM tp_journey_segment s "
                        + "WHERE s.journey_id = :id ORDER BY s.seq_no",
                new MapSqlParameterSource("id", journeyId),
                (rs, n) -> new JourneySegmentRow(
                        0L, 0L, rs.getLong("id"), rs.getInt("seq_no"), rs.getLong("project_id"),
                        rs.getString("target_kind"),
                        (Long) rs.getObject("asset_row_id"),
                        (Long) rs.getObject("case_id"),
                        rs.getString("params_override_json"),
                        null));
        return new PlanSnapshot(journeyId, journeyName, segments);
    }

    private List<JourneySegmentRow> createRunRows(PlanSnapshot plan) {
        return transactionTemplate.execute(status -> {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("journey", plan.journeyId());
            KeyHolder holder = new GeneratedKeyHolder();
            jdbc.update("INSERT INTO tp_journey_run (journey_id, status) VALUES (:journey, 1)",
                    params, holder, new String[]{"id"});
            long run = holder.getKey().longValue();
            List<JourneySegmentRow> rows = new ArrayList<>();
            for (JourneySegmentRow segment : plan.segments()) {
                Long rowId = jdbc.queryForObject(
                        "INSERT INTO tp_journey_run_segment "
                                + "(run_id, segment_id, project_id, seq_no, status) "
                                + "VALUES (:run, :segment, :project, :seq, 0) RETURNING id",
                        new MapSqlParameterSource()
                                .addValue("run", run)
                                .addValue("segment", segment.planSegmentId())
                                .addValue("project", segment.projectId())
                                .addValue("seq", segment.seqNo()),
                        Long.class);
                rows.add(segment.withRunIds(run, rowId));
            }
            return rows;
        });
    }

    private void markSegmentLaunched(long runSegmentId, Long batchId) {
        jdbc.update("UPDATE tp_journey_run_segment SET batch_id = :batch, status = 1, "
                        + "started_at = now(), updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("batch", batchId).addValue("id", runSegmentId));
    }

    private void failRun(long operatorId, long runId, JourneySegmentRow segment,
                         String failureCode, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbc.update("UPDATE tp_journey_run_segment SET status = 3, failure_code = :code, "
                            + "failure_message = :message, finished_at = now(), updated_at = now() "
                            + "WHERE id = :id AND status IN (0, 1)",
                    new MapSqlParameterSource()
                            .addValue("code", failureCode)
                            .addValue("message", message)
                            .addValue("id", segment.runSegmentId()));
            jdbc.update("UPDATE tp_journey_run SET status = 3, finished_at = now(), "
                            + "updated_at = now() WHERE id = :id AND status = 1",
                    new MapSqlParameterSource("id", runId));
        });
    }

    private void skipDownstream(long runId, List<JourneySegmentRow> segments, int fromIndex) {
        for (int j = fromIndex; j < segments.size(); j++) {
            jdbc.update("UPDATE tp_journey_run_segment SET status = 4, "
                            + "failure_code = 'SKIPPED_UPSTREAM', "
                            + "failure_message = 'Upstream segment failed', updated_at = now() "
                            + "WHERE run_id = :run AND seq_no = :seq AND status = 0",
                    new MapSqlParameterSource()
                            .addValue("run", runId)
                            .addValue("seq", segments.get(j).seqNo()));
        }
    }

    private static <T> T first(List<T> rows, java.util.function.Supplier<RuntimeException> missing) {
        if (rows.isEmpty()) {
            throw missing.get();
        }
        return rows.get(0);
    }

    /** Plan row as stored on tp_journey_segment plus its run-side row id. */
    public record JourneySegmentRow(long runId,
                                    long runSegmentId,
                                    long planSegmentId,
                                    int seqNo,
                                    long projectId,
                                    String targetKind,
                                    Long assetRowId,
                                    Long caseId,
                                    String paramsOverrideJson,
                                    Long envId) {
        JourneySegmentRow withRunIds(long run, long rowId) {
            return new JourneySegmentRow(run, rowId, planSegmentId, seqNo, projectId, targetKind,
                    assetRowId, caseId, paramsOverrideJson, envId);
        }
    }

    /** Journey header plus ordered plan segments captured at run start. */
    public record PlanSnapshot(long journeyId, String journeyName,
                               List<JourneySegmentRow> segments) {
    }
}
