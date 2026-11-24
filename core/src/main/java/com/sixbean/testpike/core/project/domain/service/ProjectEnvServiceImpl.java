package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.api.dto.DeleteCheckResult;
import com.sixbean.testpike.core.common.domain.component.DeleteGuard;
import com.sixbean.testpike.core.project.api.dto.EnvDeployBatchRef;
import com.sixbean.testpike.core.project.api.dto.EnvDeployInfo;
import com.sixbean.testpike.core.project.api.dto.EnvParamItem;
import com.sixbean.testpike.core.project.api.dto.EnvParamsInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.component.EnvParamCipherSupport;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import com.sixbean.testpike.core.project.domain.component.ProjectEnvRefusals;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;


@Service
public class ProjectEnvServiceImpl implements ProjectEnvService {

    private static final String SELECT_COLUMNS = """
            id, project_id, name, env_type, base_url, config_json, end_types, browsers,
            health_status, health_checked_at, health_msg, version_snapshot, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ProjectService projectService;
    private final DeleteGuard deleteGuard;
    private final EnvParamCipherSupport envParamCipherSupport;
    private final EnvTlsSupport envTlsSupport;

    private final com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder
            batchRuntimeChangeRecorder;

    public ProjectEnvServiceImpl(JdbcTemplate jdbcTemplate, ProjectService projectService,
                                 DeleteGuard deleteGuard, EnvParamCipherSupport envParamCipherSupport,
                                 EnvTlsSupport envTlsSupport,
                                 com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder
                                         batchRuntimeChangeRecorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectService = projectService;
        this.deleteGuard = deleteGuard;
        this.envParamCipherSupport = envParamCipherSupport;
        this.envTlsSupport = envTlsSupport;
        this.batchRuntimeChangeRecorder = batchRuntimeChangeRecorder;
    }

    public List<ProjectEnvInfo> list(long projectId) {
        return jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project_env WHERE project_id = ? ORDER BY id",
                this::mapRow, projectId);
    }

    @Audit(objectType = "project_env", action = "create", objectId = "#result ?: #input.name()", after = "#result")
    public long create(long projectId, ProjectEnvInput input) {
        requireProject(projectId);
        validate(input);
        ensureTypeUnique(projectId, input.envType(), null);
        try {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO tp_project_env
                        (project_id, name, env_type, base_url, config_json, end_types, browsers, version_snapshot)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id
                    """, Long.class, projectId, input.name().trim(), input.envType(),
                    trimToNull(input.baseUrl()), trimToNull(input.configJson()),
                    defaultEndTypes(input), defaultBrowsers(input), trimToNull(input.versionSnapshot()));
        } catch (DuplicateKeyException e) {
            throw new ServiceException(ProjectEnvRefusals.duplicateEnvName(input.name().trim()),
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Audit(objectType = "project_env", action = "update", objectId = "#envId", after = "#result")
    public ProjectEnvInfo update(long projectId, long envId, ProjectEnvInput input) {
        requireProject(projectId);
        validate(input);
        ensureTypeUnique(projectId, input.envType(), envId);
        envParamCipherSupport.assertParamsSectionUnchanged(
                getInternal(projectId, envId).configJson(), input.configJson());
        envTlsSupport.assertTlsSectionUnchanged(
                getInternal(projectId, envId).configJson(), input.configJson());
        int updated = jdbcTemplate.update("""
                UPDATE tp_project_env SET name = ?, env_type = ?, base_url = ?,
                config_json = ?::jsonb, end_types = ?, browsers = ?, version_snapshot = ?,
                health_fail_streak = 0, updated_at = now()
                WHERE id = ? AND project_id = ?
                """, input.name().trim(), input.envType(), trimToNull(input.baseUrl()),
                trimToNull(input.configJson()), defaultEndTypes(input), defaultBrowsers(input),
                trimToNull(input.versionSnapshot()), envId, projectId);
        if (updated == 0) {
            throw new ServiceException(ProjectEnvRefusals.envMissing(), BizErrorCode.NOT_FOUND);
        }
        batchRuntimeChangeRecorder.recordEnvChange(envId,
                com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder.KIND_ENV);
        return get(projectId, envId);
    }

    @Audit(objectType = "project_env", action = "delete", objectId = "#envId")
    public void delete(long projectId, long envId) {
        delete(projectId, envId, false);
    }

    @Audit(objectType = "project_env", action = "delete", objectId = "#envId")
    public void delete(long projectId, long envId, boolean force) {
        requireProject(projectId);
        getInternal(projectId, envId);
        DeleteCheckResult check = deleteGuard.checkEnv(envId);
        if (!check.deletable() && !force) {
            throw new ServiceException(ProjectEnvRefusals.deleteBlocked(),
                    BizErrorCode.RESOURCE_CONFLICT, DeleteGuard.joinMessages(check.refs()));
        }
        jdbcTemplate.update("UPDATE tp_exec_batch SET env_id = NULL WHERE env_id = ?", envId);
        jdbcTemplate.update("DELETE FROM tp_data_factory_run WHERE factory_id IN (SELECT id FROM tp_data_factory WHERE env_id = ?)", envId);
        jdbcTemplate.update("DELETE FROM tp_data_factory WHERE env_id = ?", envId);
        jdbcTemplate.update("DELETE FROM tp_data_pool_item WHERE env_id = ?", envId);
        jdbcTemplate.update("DELETE FROM tp_db_connection WHERE env_id = ?", envId);
        int deleted = jdbcTemplate.update(
                "DELETE FROM tp_project_env WHERE id = ? AND project_id = ?", envId, projectId);
        if (deleted == 0) {
            throw new ServiceException(ProjectEnvRefusals.envMissing(), BizErrorCode.NOT_FOUND);
        }
    }

    @Override
    public List<EnvParamItem> listParams(long projectId, long envId) {
        return envParamCipherSupport.readItems(getInternal(projectId, envId).configJson());
    }

    @Override
    @Audit(objectType = "project_env", action = "update_params", objectId = "#envId")
    public List<EnvParamItem> saveParams(long projectId, long envId, EnvParamsInput input) {
        ProjectEnvInfo current = getInternal(projectId, envId);
        String merged = envParamCipherSupport.mergeParams(current.configJson(),
                input == null ? Map.of() : input.params());
        jdbcTemplate.update(
                "UPDATE tp_project_env SET config_json = ?::jsonb, updated_at = now()"
                        + " WHERE id = ? AND project_id = ?",
                merged, envId, projectId);
        batchRuntimeChangeRecorder.recordEnvChange(envId,
                com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder.KIND_ENV);
        return envParamCipherSupport.readItems(merged);
    }

    private ProjectEnvInfo getInternal(long projectId, long envId) {
        List<ProjectEnvInfo> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project_env WHERE id = ? AND project_id = ?",
                this::mapRow, envId, projectId);
        if (rows.isEmpty()) {
            throw new ServiceException(ProjectEnvRefusals.envMissing(), BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public ProjectEnvInfo get(long projectId, long envId) {
        return getInternal(projectId, envId);
    }

    @Override
    public ProjectEnvInfo getByEnvId(long envId) {
        List<ProjectEnvInfo> rows = jdbcTemplate.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_project_env WHERE id = ?",
                this::mapRow, envId);
        if (rows.isEmpty()) {
            throw new ServiceException(ProjectEnvRefusals.envGoneById(envId), BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    @Audit(objectType = "project_env", action = "deploy", objectId = "#envId", after = "#version")
    public EnvDeployInfo recordDeploy(long projectId, long envId, String version, long operatorId) {
        if (version == null || version.isBlank()) {
            throw new ServiceException(ProjectEnvRefusals.deployVersionBlank(), BizErrorCode.VALIDATION_ERROR);
        }
        if (version.trim().length() > 50) {
            throw new ServiceException(ProjectEnvRefusals.deployVersionTooLong(version), BizErrorCode.VALIDATION_ERROR);
        }
        getInternal(projectId, envId);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(Map.of("deployedVersion",
                jdbcTemplate.queryForObject(
                        "SELECT COALESCE(deployed_version, '') FROM tp_project_env WHERE id = ?",
                        String.class, envId))));
        jdbcTemplate.update("""
                UPDATE tp_project_env
                SET deployed_version = ?, deployed_at = now(), deployed_by = ?,
                    version_snapshot = ?, updated_at = now()
                WHERE id = ? AND project_id = ?
                """, version.trim(), operatorId, version.trim(), envId, projectId);
        return getDeployInfo(projectId, envId);
    }

    @Override
    public EnvDeployInfo getDeployInfo(long projectId, long envId) {
        getInternal(projectId, envId);
        List<EnvDeployInfo> rows = jdbcTemplate.query("""
                SELECT COALESCE(e.deployed_version, '') AS deployed_version,
                       to_char(e.deployed_at, 'YYYY-MM-DD HH24:MI:SS') AS deployed_at,
                       e.deployed_by,
                       COALESCE(u.display_name, '') AS deployed_by_name
                FROM tp_project_env e
                LEFT JOIN tp_user u ON u.id = e.deployed_by
                WHERE e.id = ? AND e.project_id = ?
                """, (rs, rowNum) -> new EnvDeployInfo(
                        rs.getString("deployed_version").isEmpty() ? null : rs.getString("deployed_version"),
                        rs.getString("deployed_at"),
                        rs.getObject("deployed_by") == null ? null : rs.getLong("deployed_by"),
                        rs.getString("deployed_by_name"), null),
                envId, projectId);
        EnvDeployInfo base = rows.get(0);
        String deployedVersion = base.deployedVersion();
        List<EnvDeployBatchRef> batches = jdbcTemplate.query("""
                SELECT b.id, b.batch_no, b.name, COALESCE(b.env_version_snapshot, '') AS env_version,
                       to_char(b.finished_at, 'YYYY-MM-DD HH24:MI:SS') AS finished_at
                FROM tp_exec_batch b
                WHERE b.env_id = ? AND b.project_id = ?
                ORDER BY b.id DESC LIMIT 20
                """, (rs, rowNum) -> new EnvDeployBatchRef(rs.getLong("id"),
                        rs.getString("batch_no"), rs.getString("name"),
                        rs.getString("env_version"),
                        rs.getString("env_version").equals(deployedVersion)
                                && deployedVersion != null,
                        rs.getString("finished_at")), envId, projectId);
        return new EnvDeployInfo(base.deployedVersion(), base.deployedAt(), base.deployedBy(),
                base.deployedByName(), batches);
    }

    private void requireProject(long projectId) {
        if (!projectService.exists(projectId)) {
            throw new ServiceException(ProjectEnvRefusals.projectMissing(projectId), BizErrorCode.NOT_FOUND);
        }
    }

    private void validate(ProjectEnvInput input) {
        if (input.name() == null || input.name().isBlank() || input.name().trim().length() > 50) {
            throw new ServiceException(ProjectEnvRefusals.nameInvalid(input.name()), BizErrorCode.VALIDATION_ERROR);
        }
        if (input.envType() < 1 || input.envType() > 4) {
            throw new ServiceException(ProjectEnvRefusals.typeInvalid(input.envType()), BizErrorCode.VALIDATION_ERROR);
        }
        if (input.envType() == 4 && (input.baseUrl() == null || input.baseUrl().isBlank())) {
            throw new ServiceException(ProjectEnvRefusals.prodBaseUrlMissing(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        envTlsSupport.assertSectionAllowed(input.envType(), input.configJson());
    }

    private void ensureTypeUnique(long projectId, int envType, Long excludeEnvId) {
        Integer cnt = excludeEnvId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tp_project_env WHERE project_id = ? AND env_type = ?",
                        Integer.class, projectId, envType)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tp_project_env WHERE project_id = ? AND env_type = ? AND id != ?",
                        Integer.class, projectId, envType, excludeEnvId);
        if (cnt != null && cnt > 0) {
            throw new ServiceException(ProjectEnvRefusals.typeNotUnique(envType), BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    private static String defaultEndTypes(ProjectEnvInput input) {
        return input.endTypes() == null || input.endTypes().isBlank() ? "web" : input.endTypes().trim();
    }

    private static String defaultBrowsers(ProjectEnvInput input) {
        return input.browsers() == null || input.browsers().isBlank() ? "chromium" : input.browsers().trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ProjectEnvInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectEnvInfo(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("name"),
                rs.getInt("env_type"),
                rs.getString("base_url"),
                rs.getString("config_json"),
                rs.getString("end_types"),
                rs.getString("browsers"),
                rs.getInt("health_status"),
                rs.getObject("health_checked_at", LocalDateTime.class),
                rs.getString("health_msg"),
                rs.getString("version_snapshot"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class));
    }
}
