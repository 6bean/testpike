package com.sixbean.testpike.core.visual.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.visual.domain.component.VisualBaselineRefusals;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineCandidateInput;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;
import com.sixbean.testpike.core.visual.domain.component.VisualBaselineStatus;
import com.sixbean.testpike.core.visual.domain.service.VisualBaselineService;
import com.sixbean.testpike.core.visual.infrastructure.dataobject.VisualBaselineRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;


@Service
public class VisualBaselineServiceImpl implements VisualBaselineService {

 
    static final String SCENE = "visual-baseline";

    private static final int STEP_KEY_MAX = 200;

    private static final String SELECT_COLUMNS =
            "SELECT id, project_id, step_key, source_task_id, source_artifact_key, baseline_key,"
                    + " status, diff_tolerance, confirmed_by, created_at, confirmed_at"
                    + " FROM tp_visual_baseline";

    private final NamedParameterJdbcTemplate named;
    private final ArtifactStorage artifactStorage;

    public VisualBaselineServiceImpl(NamedParameterJdbcTemplate named, ArtifactStorage artifactStorage) {
        this.named = named;
        this.artifactStorage = artifactStorage;
    }

    @Override
    @Transactional
    public VisualBaselineInfo recordCandidate(VisualBaselineCandidateInput input) {
        if (input.projectId() == null || input.projectId() <= 0) {
            throw new ServiceException(VisualBaselineRefusals.projectMissing(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.stepKey() == null || input.stepKey().isBlank()) {
            throw new ServiceException(VisualBaselineRefusals.stepKeyBlank(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.stepKey().length() > STEP_KEY_MAX) {
            throw new ServiceException(VisualBaselineRefusals.stepKeyTooLong(STEP_KEY_MAX,
                    input.stepKey().length()), BizErrorCode.VALIDATION_ERROR);
        }
        if (input.sourceArtifactKey() == null || input.sourceArtifactKey().isBlank()) {
            throw new ServiceException(VisualBaselineRefusals.sourceKeyBlank(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        checkTolerance(input.diffTolerance());
        Long projectCount = named.queryForObject("SELECT count(*) FROM tp_project WHERE id = :projectId",
                new MapSqlParameterSource("projectId", input.projectId()), Long.class);
        if (projectCount == null || projectCount == 0L) {
            throw new ServiceException(VisualBaselineRefusals.projectNotFound(input.projectId()),
                    BizErrorCode.NOT_FOUND);
        }
        Long id = named.queryForObject(
                "INSERT INTO tp_visual_baseline"
                        + " (project_id, step_key, source_task_id, source_artifact_key, status, diff_tolerance)"
                        + " VALUES (:projectId, :stepKey, :sourceTaskId, :sourceArtifactKey,"
                        + "         " + VisualBaselineStatus.PENDING + ", :diffTolerance)"
                        + " ON CONFLICT (project_id, step_key) WHERE status = " + VisualBaselineStatus.PENDING
                        + " DO UPDATE SET source_task_id = EXCLUDED.source_task_id,"
                        + "    source_artifact_key = EXCLUDED.source_artifact_key,"
                        + "    diff_tolerance = EXCLUDED.diff_tolerance"
                        + " RETURNING id",
                new MapSqlParameterSource()
                        .addValue("projectId", input.projectId())
                        .addValue("stepKey", input.stepKey().trim())
                        .addValue("sourceTaskId", input.sourceTaskId())
                        .addValue("sourceArtifactKey", input.sourceArtifactKey().trim())
                        .addValue("diffTolerance", input.diffTolerance()),
                Long.class);
        return getById(id);
    }

    @Override
    @Transactional
    public VisualBaselineInfo confirm(long id, Long userId) {
        VisualBaselineInfo current = getById(id);
        if (current.status() != VisualBaselineStatus.PENDING) {
            throw new ServiceException(VisualBaselineRefusals.confirmNotPending(
                    current.statusLabel()), BizErrorCode.VALIDATION_ERROR);
        }
        byte[] bytes = artifactStorage.readBytesQuietly(current.sourceArtifactKey());
        if (bytes == null || bytes.length == 0) {
            throw new ServiceException(VisualBaselineRefusals.sourceBytesEmpty(
                    current.sourceArtifactKey()), BizErrorCode.VALIDATION_ERROR);
        }
        String safeName = current.stepKey().replaceAll("[^A-Za-z0-9._-]", "-") + ".png";
        String baselineKey = artifactStorage.buildProjectObjectKey(
                current.projectId(), SCENE, safeName);
        artifactStorage.putBytesForProject(current.projectId(), baselineKey, bytes, "image/png");
        named.update("UPDATE tp_visual_baseline SET status = " + VisualBaselineStatus.SUPERSEDED
                        + " WHERE project_id = :projectId AND step_key = :stepKey AND status = "
                        + VisualBaselineStatus.CONFIRMED,
                new MapSqlParameterSource().addValue("projectId", current.projectId())
                        .addValue("stepKey", current.stepKey()));
        named.update("UPDATE tp_visual_baseline SET status = " + VisualBaselineStatus.CONFIRMED
                        + ", baseline_key = :baselineKey, confirmed_by = :userId, confirmed_at = now()"
                        + " WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id)
                        .addValue("baselineKey", baselineKey)
                        .addValue("userId", userId));
        return getById(id);
    }

    @Override
    @Transactional
    public void reject(long id, Long userId) {
        VisualBaselineInfo current = getById(id);
        if (current.status() != VisualBaselineStatus.PENDING) {
            throw new ServiceException(VisualBaselineRefusals.rejectNotPending(
                    current.statusLabel()), BizErrorCode.VALIDATION_ERROR);
        }
        named.update("DELETE FROM tp_visual_baseline WHERE id = :id AND status = "
                        + VisualBaselineStatus.PENDING,
                new MapSqlParameterSource("id", id));
    }

    @Override
    public VisualBaselineInfo getById(long id) {
        List<VisualBaselineInfo> rows = query(new MapSqlParameterSource("id", id), " WHERE id = :id");
        if (rows.isEmpty()) {
            throw new ServiceException(VisualBaselineRefusals.baselineMissing(id), BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public VisualBaselineInfo findConfirmed(long projectId, String stepKey) {
        return query(new MapSqlParameterSource().addValue("projectId", projectId)
                        .addValue("stepKey", stepKey),
                " WHERE project_id = :projectId AND step_key = :stepKey AND status = "
                        + VisualBaselineStatus.CONFIRMED)
                .stream().findFirst().orElse(null);
    }

    @Override
    public List<VisualBaselineInfo> listByStep(long projectId, String stepKey) {
        return query(new MapSqlParameterSource().addValue("projectId", projectId)
                        .addValue("stepKey", stepKey),
                " WHERE project_id = :projectId AND step_key = :stepKey ORDER BY id DESC");
    }

    @Override
    public List<VisualBaselineInfo> listByCase(long projectId, long caseId) {
        return query(new MapSqlParameterSource().addValue("projectId", projectId)
                        .addValue("prefix", "case:" + caseId + ":%"),
                " WHERE project_id = :projectId AND step_key LIKE :prefix ORDER BY id DESC");
    }

    @Override
    public String viewUrl(long id) {
        VisualBaselineInfo current = getById(id);
        String key = current.status() == VisualBaselineStatus.PENDING
                ? current.sourceArtifactKey() : current.baselineKey();
        if (key == null || key.isBlank()) {
            throw new ServiceException(VisualBaselineRefusals.noViewableShot(
                    current.statusLabel(), id), BizErrorCode.VALIDATION_ERROR);
        }
        return artifactStorage.grantViewUrl(key);
    }

    @Override
    public byte[] readBaselineBytes(String baselineKey) {
        return artifactStorage.readBytesQuietly(baselineKey);
    }

    @Override
    public String grantBaselineViewUrl(String baselineKey) {
        return artifactStorage.grantViewUrl(baselineKey);
    }

    
    private List<VisualBaselineInfo> query(MapSqlParameterSource params, String whereOrderBy) {
        return named.query(SELECT_COLUMNS + whereOrderBy, params,
                new BeanPropertyRowMapper<>(VisualBaselineRow.class))
                .stream().map(VisualBaselineRow::toInfo).toList();
    }

    private static void checkTolerance(Double tolerance) {
        if (tolerance != null && (tolerance < 0 || tolerance > 1)) {
            throw new ServiceException(VisualBaselineRefusals.toleranceInvalid(tolerance),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
