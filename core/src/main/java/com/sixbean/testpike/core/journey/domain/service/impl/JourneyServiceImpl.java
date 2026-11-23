package com.sixbean.testpike.core.journey.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.asset.domain.service.SharedAssetReferenceService;
import com.sixbean.testpike.core.journey.api.dto.JourneyCreateInput;
import com.sixbean.testpike.core.journey.api.dto.JourneyInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneyPageView;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInfo;
import com.sixbean.testpike.core.journey.api.dto.JourneySegmentInput;
import com.sixbean.testpike.core.journey.domain.service.JourneyService;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Journey plan surface over tp_journey / tp_journey_segment (V380, segment A).
 * All argument and existence validation runs before the first SQL write so a
 * CHECK/FK violation can never poison the surrounding transaction (aborted-state
 * discipline shared with the asset domain). Segment targets resolve against the
 * published asset chain table; the reference pin for an asset segment is registered
 * through the single-sourced SharedAssetReferenceService with host kind
 * "journey_segment" — the asset pool's "who uses me" view therefore names journeys
 * without a second protocol. Case-target existence is enforced when the segment is
 * expanded into a batch (segment B), where the batch service already validates its
 * case list server-side.
 */
@Service
public class JourneyServiceImpl implements JourneyService {

    /** Host kind literal this domain registers on tp_asset_reference (never re-declared). */
    public static final String SEGMENT_PIN_KIND = "journey_segment";

    private static final RowMapper<JourneyInfo> HEADER_MAPPER = (rs, rowNum) -> new JourneyInfo(
            rs.getLong("id"),
            rs.getLong("program_id"),
            rs.getString("name"),
            rs.getLong("owner_user_id"),
            rs.getInt("status"),
            List.of());

    private static final RowMapper<JourneySegmentInfo> SEGMENT_MAPPER = (rs, rowNum) -> new JourneySegmentInfo(
            rs.getLong("id"),
            rs.getInt("seq_no"),
            rs.getLong("project_id"),
            rs.getString("target_kind"),
            (Long) rs.getObject("asset_row_id"),
            (Long) rs.getObject("case_id"),
            rs.getString("params_override_json"));

    private final NamedParameterJdbcTemplate jdbc;
    private final SharedAssetReferenceService referenceService;

    public JourneyServiceImpl(NamedParameterJdbcTemplate jdbc,
                              SharedAssetReferenceService referenceService) {
        this.jdbc = jdbc;
        this.referenceService = referenceService;
    }

    @Override
    @Transactional
    public JourneyInfo createJourney(long operatorId, JourneyCreateInput input) {
        if (input == null || input.programId() <= 0 || input.ownerUserId() <= 0
                || input.name() == null || input.name().isBlank()) {
            throw new ServiceException("Journey requires a positive program, a positive owner "
                    + "user and a non-blank name");
        }
        Integer program = jdbc.queryForObject(
                "SELECT count(*) FROM tp_program WHERE id = :id AND state = 1",
                new MapSqlParameterSource("id", input.programId()), Integer.class);
        if (program == null || program == 0) {
            throw new ServiceException("Program " + input.programId()
                    + " does not exist or is archived; journeys can only be planned under an active program");
        }
        Integer dup = jdbc.queryForObject(
                "SELECT count(*) FROM tp_journey WHERE program_id = :program AND name = :name",
                new MapSqlParameterSource().addValue("program", input.programId())
                        .addValue("name", input.name().trim()), Integer.class);
        if (dup != null && dup > 0) {
            throw new ServiceException("Journey name \"" + input.name().trim()
                    + "\" is already taken inside this program");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO tp_journey (program_id, name, owner_user_id, created_by) "
                        + "VALUES (:program, :name, :owner, :operator)",
                new MapSqlParameterSource().addValue("program", input.programId())
                        .addValue("name", input.name().trim())
                        .addValue("owner", input.ownerUserId())
                        .addValue("operator", operatorId),
                keyHolder, new String[]{"id"});
        return getJourney(keyHolder.getKey().longValue());
    }

    @Override
    @Transactional
    public JourneySegmentInfo addSegment(long journeyId, JourneySegmentInput input) {
        if (journeyId <= 0 || input == null || input.seqNo() < 1 || input.projectId() <= 0) {
            throw new ServiceException("Segment needs an existing journey, a seqNo starting at 1 "
                    + "and a positive owning project");
        }
        Integer journey = jdbc.queryForObject(
                "SELECT count(*) FROM tp_journey WHERE id = :id",
                new MapSqlParameterSource("id", journeyId), Integer.class);
        if (journey == null || journey == 0) {
            throw new ServiceException("Journey " + journeyId + " does not exist");
        }
        boolean assetTarget = "asset".equals(input.targetKind());
        boolean caseTarget = "case".equals(input.targetKind());
        if (!assetTarget && !caseTarget) {
            throw new ServiceException("Segment target kind must be \"asset\" or \"case\", got \""
                    + input.targetKind() + "\"");
        }
        if (assetTarget && (input.assetRowId() == null || input.assetRowId() <= 0 || input.caseId() != null)) {
            throw new ServiceException("Asset segment must point at exactly one published "
                    + "asset version row (assetRowId), never at a case");
        }
        if (caseTarget && (input.caseId() == null || input.caseId() <= 0 || input.assetRowId() != null)) {
            throw new ServiceException("Case segment must point at exactly one case (caseId), "
                    + "never at an asset row");
        }
        Integer project = jdbc.queryForObject(
                "SELECT count(*) FROM tp_project WHERE id = :id",
                new MapSqlParameterSource("id", input.projectId()), Integer.class);
        if (project == null || project == 0) {
            throw new ServiceException("Segment project " + input.projectId() + " does not exist");
        }
        if (assetTarget) {
            Integer asset = jdbc.queryForObject(
                    "SELECT count(*) FROM tp_asset WHERE id = :id AND status = 1",
                    new MapSqlParameterSource("id", input.assetRowId()), Integer.class);
            if (asset == null || asset == 0) {
                throw new ServiceException("Shared asset version row " + input.assetRowId()
                        + " does not exist or is withdrawn; segments can only pin published rows");
            }
        }
        Integer dupSeq = jdbc.queryForObject(
                "SELECT count(*) FROM tp_journey_segment WHERE journey_id = :journey AND seq_no = :seq",
                new MapSqlParameterSource().addValue("journey", journeyId)
                        .addValue("seq", input.seqNo()), Integer.class);
        if (dupSeq != null && dupSeq > 0) {
            throw new ServiceException("Segment order " + input.seqNo()
                    + " is already used inside this journey; keep one unique sequence per segment");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO tp_journey_segment (journey_id, seq_no, project_id, target_kind, "
                        + "asset_row_id, case_id, params_override_json) VALUES (:journey, :seq, "
                        + ":project, :kind, :assetRow, :caseId, :overrides)",
                new MapSqlParameterSource().addValue("journey", journeyId)
                        .addValue("seq", input.seqNo())
                        .addValue("project", input.projectId())
                        .addValue("kind", input.targetKind())
                        .addValue("assetRow", assetTarget ? input.assetRowId() : null)
                        .addValue("caseId", caseTarget ? input.caseId() : null)
                        .addValue("overrides", input.paramsOverrideJson()),
                keyHolder, new String[]{"id"});
        long segmentId = keyHolder.getKey().longValue();
        if (assetTarget) {
            referenceService.pin(input.projectId(), input.assetRowId(),
                    SEGMENT_PIN_KIND, segmentId);
        }
        return segmentInfo(segmentId);
    }

    @Override
    public JourneyInfo getJourney(long journeyId) {
        List<JourneyInfo> header = jdbc.query(
                "SELECT id, program_id, name, owner_user_id, status FROM tp_journey WHERE id = :id",
                new MapSqlParameterSource("id", journeyId), HEADER_MAPPER);
        if (header.isEmpty()) {
            throw new ServiceException("Journey " + journeyId + " does not exist");
        }
        List<JourneySegmentInfo> segments = jdbc.query(
                "SELECT id, seq_no, project_id, target_kind, asset_row_id, case_id, params_override_json "
                        + "FROM tp_journey_segment WHERE journey_id = :id ORDER BY seq_no",
                new MapSqlParameterSource("id", journeyId), SEGMENT_MAPPER);
        JourneyInfo base = header.get(0);
        return new JourneyInfo(base.id(), base.programId(), base.name(),
                base.ownerUserId(), base.status(), segments);
    }

    /** Hard ceiling per request window (S-group contract, same posture as the monitor wall). */
    private static final int MAX_PAGE_SIZE = 200;

    @Override
    public JourneyPageView pageJourneys(long programId, int page, int pageSize, String keyword) {
        if (programId <= 0) {
            throw new ServiceException("Journey listing requires a positive program id, got " + programId);
        }
        if (page < 1) {
            throw new ServiceException("Journey listing page numbers start at 1, got " + page);
        }
        if (pageSize < 1) {
            throw new ServiceException("Journey listing page size must be positive, got " + pageSize);
        }
        int capped = Math.min(pageSize, MAX_PAGE_SIZE);
        String pattern = keyword == null || keyword.isBlank()
                ? null : "%" + keyword.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        String where = pattern == null
                ? "program_id = :program"
                : "program_id = :program AND lower(name) LIKE :kw";
        MapSqlParameterSource countParams = new MapSqlParameterSource("program", programId);
        if (pattern != null) {
            countParams.addValue("kw", pattern);
        }
        Long counted = jdbc.queryForObject(
                "SELECT count(*) FROM tp_journey WHERE " + where, countParams, Long.class);
        long total = 0L;
        if (counted != null) {
            total = counted;
        }
        MapSqlParameterSource windowParams = new MapSqlParameterSource("program", programId);
        if (pattern != null) {
            windowParams.addValue("kw", pattern);
        }
        windowParams.addValue("limit", capped);
        windowParams.addValue("offset", (long) (page - 1) * capped);
        List<JourneyInfo> rows = jdbc.query(
                "SELECT id, program_id, name, owner_user_id, status FROM tp_journey WHERE " + where
                        + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                windowParams, HEADER_MAPPER);
        return new JourneyPageView(rows, total, page, capped);
    }

    private JourneySegmentInfo segmentInfo(long segmentId) {
        List<JourneySegmentInfo> rows = jdbc.query(
                "SELECT id, seq_no, project_id, target_kind, asset_row_id, case_id, params_override_json "
                        + "FROM tp_journey_segment WHERE id = :id",
                new MapSqlParameterSource("id", segmentId), SEGMENT_MAPPER);
        return rows.get(0);
    }
}
