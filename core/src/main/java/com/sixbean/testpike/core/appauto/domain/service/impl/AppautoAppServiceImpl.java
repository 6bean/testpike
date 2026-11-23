package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPackageGrantView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.AppautoPlatformWhitelist;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAppService;
import com.sixbean.testpike.core.appauto.infrastructure.dataobject.AppautoApp;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;


@Service
public class AppautoAppServiceImpl implements AppautoAppService {

    private static final Logger log = LoggerFactory.getLogger(AppautoAppServiceImpl.class);

    
    private static final String SCENE_APP_PACKAGE = "app-package";

    
    private static final String ARTIFACT_CONTENT_TYPE = "application/octet-stream";

    private static final String SELECT_COLUMNS =
            "id, project_id, name, package_name, version, platform_code, permission_rule_ref, "
                    + "apk_artifact_key, artifact_size_bytes, artifact_sha256, source, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;
    private final ArtifactStorage artifactStorage;
    private final AppautoConfigReader configReader;
    private final ExecTaskService execTaskService;
    private final AppautoPlatformWhitelist platformWhitelist;

    public AppautoAppServiceImpl(NamedParameterJdbcTemplate jdbc, ArtifactStorage artifactStorage,
                                 AppautoConfigReader configReader, ExecTaskService execTaskService,
                                 AppautoPlatformWhitelist platformWhitelist) {
        this.jdbc = jdbc;
        this.artifactStorage = artifactStorage;
        this.configReader = configReader;
        this.execTaskService = execTaskService;
        this.platformWhitelist = platformWhitelist;
    }

    @Override
    @Audit(objectType = "appauto_app", action = "create", objectId = "#result")
    public long create(long projectId, AppautoAppInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            throw new ServiceException("应用名称不能为空——请在「被测应用档案」新建表单填写「应用名称」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.packageName() == null || input.packageName().isBlank()) {
            throw new ServiceException("应用包名不能为空——请在「被测应用档案」新建表单填写「应用包名」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String platform = normalizePlatform(input.platformCode());
        String version = input.version() == null || input.version().isBlank()
                ? null : input.version().trim();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("name", input.name().trim())
                .addValue("packageName", input.packageName().trim())
                .addValue("version", version)
                .addValue("platformCode", platform)
                .addValue("permissionRuleRef", blankToNull(input.permissionRuleRef()));
        try {
            List<Long> inserted = jdbc.queryForList(
                    "INSERT INTO tp_appauto_app (project_id, name, package_name, version, platform_code, "
                            + "permission_rule_ref, source) "
                            + "VALUES (:projectId, :name, :packageName, :version, :platformCode, "
                            + ":permissionRuleRef, 'MANUAL') "
                            + (version == null ? "" : "ON CONFLICT (project_id, package_name, version) DO NOTHING ")
                            + "RETURNING id",
                    params, Long.class);
            if (!inserted.isEmpty()) {
                return inserted.get(0);
            }
            return overwriteExisting(params);
        } catch (DuplicateKeyException e) {
            return overwriteExisting(params);
        }
    }

    
    private long overwriteExisting(MapSqlParameterSource params) {
        String matchVersion = params.getValue("version") == null
                ? "version IS NULL" : "version = :version";
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM tp_appauto_app WHERE project_id = :projectId "
                        + "AND package_name = :packageName AND " + matchVersion,
                params, Long.class);
        if (existing.isEmpty()) {
            throw new ServiceException("应用档案建档冲突（同包名同版本已存在），请到「被测应用档案」确认既有档案后重试",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        long id = existing.get(0);
        jdbc.update("UPDATE tp_appauto_app SET name = :name, platform_code = :platformCode, "
                        + "permission_rule_ref = :permissionRuleRef, updated_at = now() WHERE id = :id",
                params.addValue("id", id));
        return id;
    }

    @Override
    public AppautoAppInfo get(long id) {
        List<AppautoAppInfo> rows = queryInfos("id = :id",
                new MapSqlParameterSource("id", id), "应用档案不存在（id=" + id + "）");
        return rows.get(0);
    }

    @Override
    public List<AppautoAppInfo> list(long projectId) {
        return queryInfos("project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId), null);
    }

    @Override
    @Audit(objectType = "appauto_app", action = "upload-package", objectId = "#id")
    public String uploadPackage(long id, byte[] content, String fileName) {
        AppautoApp app = requireApp(id);
        if (content == null || content.length == 0) {
            throw new ServiceException("包文件内容为空，拒收——请在「被测应用档案」的「上传包」重新选择安装包文件",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long maxBytes = configReader.pkgMaxSizeBytes();
        if (content.length > maxBytes) {
            throw new ServiceException("包文件 " + content.length + " 字节，超过上传上限 "
                    + maxBytes + " 字节——请改传不超限的安装包，或到「系统设置」调高 appauto.pkg-max-size 后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String safeName = fileName == null || fileName.isBlank() ? "app-package.bin" : fileName;
        String objectKey = artifactStorage.buildProjectObjectKey(
                app.getProjectId(), SCENE_APP_PACKAGE, safeName);
        artifactStorage.putBytesForProject(app.getProjectId(), objectKey, content, ARTIFACT_CONTENT_TYPE);
        jdbc.update("UPDATE tp_appauto_app SET apk_artifact_key = :key, artifact_size_bytes = :size, "
                        + "artifact_sha256 = :sha, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("key", objectKey)
                        .addValue("size", (long) content.length)
                        .addValue("sha", sha256Hex(content))
                        .addValue("id", id));
        String previousKey = app.getApkArtifactKey();
        if (previousKey != null && !previousKey.isBlank() && !previousKey.equals(objectKey)) {
            artifactStorage.deleteQuietly(previousKey);
        }
        log.info("应用包工件归档完成: appId={} key={} size={}", id, objectKey, content.length);
        return objectKey;
    }

    @Override
    @Audit(objectType = "appauto_app", action = "delete", objectId = "#id")
    public void delete(long id) {
        AppautoApp app = requireApp(id);
        jdbc.update("DELETE FROM tp_appauto_app WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (app.getApkArtifactKey() != null && !app.getApkArtifactKey().isBlank()) {
            artifactStorage.deleteQuietly(app.getApkArtifactKey());
        }
    }

    @Override
    public AppautoPackageGrantView grantPackageDownload(long taskId, Object artifactRef) {
        ExecTaskInfo task = execTaskService.get(taskId);
        AppautoApp app = resolveArchive(task.projectId(), artifactRef);
        String objectKey = app.getApkArtifactKey();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ServiceException("包档案「" + app.getName() + "」（id=" + app.getId()
                    + "）尚未上传包工件，无处可下载——请到「被测应用档案」为该档案先执行「上传包」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new AppautoPackageGrantView(app.getId(), app.getProjectId(), app.getPackageName(),
                app.getVersion(), fileNameOf(objectKey), artifactStorage.grantViewUrl(objectKey),
                app.getArtifactSha256(), app.getArtifactSizeBytes());
    }

    
    private AppautoApp resolveArchive(long projectId, Object artifactRef) {
        String ref = artifactRef == null ? "" : String.valueOf(artifactRef).trim();
        if (ref.isEmpty()) {
            throw new ServiceException("包源引用（artifactRef）不能为空"
                    + "——请在派发参数填写包档案 id 或包名（可在「被测应用档案」列表复制）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (ref.chars().allMatch(Character::isDigit)) {
            AppautoApp app = requireApp(Long.parseLong(ref));
            if (app.getProjectId() == null || app.getProjectId() != projectId) {
                throw new ServiceException("包档案（id=" + app.getId() + "）不属于任务所在项目"
                        + "（id=" + projectId + "），跨项目禁借档下载"
                        + "——请在任务所属项目的「被测应用档案」选择本项目的包档案", BizErrorCode.NOT_FOUND);
            }
            return app;
        }
        List<AppautoApp> rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_appauto_app"
                        + " WHERE project_id = :pid AND package_name = :pkg"
                        + " AND apk_artifact_key IS NOT NULL AND apk_artifact_key <> ''"
                        + " ORDER BY id DESC LIMIT 1",
                new MapSqlParameterSource().addValue("pid", projectId).addValue("pkg", ref),
                new BeanPropertyRowMapper<>(AppautoApp.class));
        if (rows.isEmpty()) {
            throw new ServiceException("项目内未找到包名「" + ref + "」已上传包工件的包档案"
                    + "——请先在「被测应用档案」建档并「上传包」后重试", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    
    private static String fileNameOf(String objectKey) {
        int idx = objectKey.lastIndexOf('/');
        return idx >= 0 && idx < objectKey.length() - 1 ? objectKey.substring(idx + 1) : objectKey;
    }

    
    private AppautoApp requireApp(long id) {
        List<AppautoApp> rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_appauto_app WHERE id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(AppautoApp.class));
        if (rows.isEmpty()) {
            throw new ServiceException("应用档案不存在（id=" + id + "）——请刷新「被测应用档案」列表确认档案仍在",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    
    private List<AppautoAppInfo> queryInfos(String where, MapSqlParameterSource params, String notFoundMessage) {
        List<AppautoApp> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM tp_appauto_app WHERE " + where + " ORDER BY id DESC",
                params, new BeanPropertyRowMapper<>(AppautoApp.class));
        if (rows.isEmpty() && notFoundMessage != null) {
            throw new ServiceException(notFoundMessage, BizErrorCode.NOT_FOUND);
        }
        return rows.stream().map(AppautoAppServiceImpl::toInfo).toList();
    }

 
    private String normalizePlatform(String raw) {
        return platformWhitelist.normalize(raw);
    }

    private static AppautoAppInfo toInfo(AppautoApp app) {
        return new AppautoAppInfo(app.getId(), app.getProjectId(), app.getName(), app.getPackageName(),
                app.getVersion(), app.getPlatformCode(), app.getPermissionRuleRef(), app.getApkArtifactKey(),
                app.getArtifactSizeBytes(), app.getArtifactSha256(), app.getSource(),
                app.getCreatedAt(), app.getUpdatedAt());
    }

    
    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(content)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺 SHA-256 摘要算法", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
