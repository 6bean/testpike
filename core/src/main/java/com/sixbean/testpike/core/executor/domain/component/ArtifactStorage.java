package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.core.executor.api.dto.ArtifactGrantView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalLong;


@Component
public class ArtifactStorage {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStorage.class);

    
    private static final String SCENE = "exec-artifact";

 
    private static final String SCENE_EXPLORE = "explore";

 
    private static final String SCENE_JUDGE = "judge-artifact";

 /** draft-bundle scene for AI mock-drafting jobs (project-routed like explore). */
    private static final String SCENE_MOCK_DRAFT = "mock-draft";

    /** tool-result scene: verbatim oversized tool output, system level. */
    private static final String SCENE_TOOL_RESULT = "tool-result";

    private final StorageRouter router;
    private final ProjectStorageLookup projectStorageLookup;

    public ArtifactStorage(StorageRouter router, ProjectStorageLookup projectStorageLookup) {
        this.router = router;
        this.projectStorageLookup = projectStorageLookup;
    }

    
    public ArtifactGrantView grantUpload(long taskId, String kind, String fileName) {
        if (taskId <= 0) {
            throw new ServiceException("任务不能为空，请确认上传请求携带任务标识"
                    + "（示例：taskId=1024）后重新发起", BizErrorCode.VALIDATION_ERROR);
        }
        String safeKind = kind == null || kind.isBlank() ? "screenshot" : kind;
        String safeName = fileName == null || fileName.isBlank() ? "step.png" : fileName;
        StorageRoute route = routeForTask(taskId);
        String objectKey = StorageKeys.build(joinPrefix(router.keyPrefix(), route.prefix()),
                taskId, SCENE, safeKind + "_" + safeName);
        StorageGrant grant = route.provider().presignPut(objectKey, router.urlTtl());
        return new ArtifactGrantView(taskId, grant.key(), grant.url());
    }

    
    public StorageGrant grantExploreUpload(long projectId, String kind, String fileName) {
        if (projectId <= 0) {
            throw new ServiceException("项目不能为空，请先在顶栏选择项目，"
                    + "或确认请求携带项目标识（示例：projectId=7）", BizErrorCode.VALIDATION_ERROR);
        }
        String safeKind = kind == null || kind.isBlank() ? "screenshot" : kind;
        String safeName = fileName == null || fileName.isBlank() ? "step.png" : fileName;
        StorageRoute route = routeForProject(projectId);
        String objectKey = StorageKeys.build(joinPrefix(router.keyPrefix(), route.prefix()),
                projectId, SCENE_EXPLORE, safeKind + "_" + safeName);
        return route.provider().presignPut(objectKey, router.urlTtl());
    }

    /**
 * Draft-bundle upload grant for one AI mock-drafting job
 * prereq; mirrors grantExploreUpload — the job row has no exec taskId, so
 * the key segment is the owning projectId and the scene is mock-draft.
 * Read/write share the same project route so a written bundle stays
 * visible through existence checks and view urls).
 *
 * @param projectId owning project of the drafting job
 * @param fileName requested file name (cleaned by StorageKeys at one point)
 */
    public StorageGrant grantMockDraftUpload(long projectId, String fileName) {
        if (projectId <= 0) {
            throw new ServiceException("项目不能为空，请先在顶栏选择项目，"
                    + "或确认请求携带项目标识（示例：projectId=7）", BizErrorCode.VALIDATION_ERROR);
        }
        String safeName = fileName == null || fileName.isBlank() ? "draft.json" : fileName;
        StorageRoute route = routeForProject(projectId);
        String objectKey = StorageKeys.build(joinPrefix(router.keyPrefix(), route.prefix()),
                projectId, SCENE_MOCK_DRAFT, safeName);
        return route.provider().presignPut(objectKey, router.urlTtl());
    }

    
    public StorageGrant grantJudgeArtifactUpload(long runId, long sampleId) {
        String objectKey = StorageKeys.build(router.keyPrefix(), 0L, SCENE_JUDGE,
                "run-" + runId + "-sample-" + sampleId + ".json");
        return router.systemProvider().presignPut(objectKey, router.urlTtl());
    }

    /**
 * Upload grant for one verbatim tool result ; mirrors
 * grantJudgeArtifactUpload — an AI-session internal dump is not a project
 * artifact, so the key takes the system-level project segment 0 and always
 * goes through the system default provider. The retrieval read-back lands
 * on the same 0-segment route, keeping write and read on one source).
 *
 * @param fileName requested name embedding the owner scope anchor
 * (task{id}-… / dialog-…); blank degrades to result.txt
 */
    public StorageGrant grantToolResultUpload(String fileName) {
        String safeName = fileName == null || fileName.isBlank() ? "result.txt" : fileName;
        String objectKey = StorageKeys.build(router.keyPrefix(), 0L, SCENE_TOOL_RESULT, safeName);
        return router.systemProvider().presignPut(objectKey, router.urlTtl());
    }

 
    public boolean exists(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        return providerForExistingKey(objectKey).exists(objectKey);
    }

    
    public void deleteQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            providerForExistingKey(objectKey).delete(objectKey);
        } catch (Exception e) {
            log.warn("产物对象删除失败（不中断清理）: key={} err={}", objectKey, e.getMessage());
        }
    }

    
    public String grantViewUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ServiceException("截图产物不能为空，请确认查看请求携带产物对象 key"
                    + "（示例：exec/1024/screenshot_step.png）", BizErrorCode.VALIDATION_ERROR);
        }
        return providerForExistingKey(objectKey).presignGet(objectKey, router.urlTtl()).url();
    }

    
    public byte[] readBytesQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        try {
            com.sixbean.testpike.common.storage.StorageProvider provider =
                    providerForExistingKey(objectKey);
            return provider.exists(objectKey) ? provider.get(objectKey) : null;
        } catch (Exception e) {
            log.warn("产物对象静默读取失败（调用方跳过）: key={} err={}", objectKey, e.getMessage());
            return null;
        }
    }

    
    public void putBytes(String objectKey, byte[] content, String contentType) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ServiceException("对象 key 不能为空，请确认写入请求携带对象 key"
                    + "（示例：artifacts/run-7/report.html）", BizErrorCode.VALIDATION_ERROR);
        }
        router.systemProvider().put(objectKey, content, contentType);
    }

    
    public void putBytesForProject(long projectId, String objectKey, byte[] content, String contentType) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ServiceException("对象 key 不能为空，请确认写入请求携带对象 key"
                    + "（示例：artifacts/run-7/report.html）", BizErrorCode.VALIDATION_ERROR);
        }
        routeForProject(projectId).provider().put(objectKey, content, contentType);
    }

    
    public String buildProjectObjectKey(long projectId, String scene, String fileName) {
        StorageRoute route = routeForProject(projectId);
        return StorageKeys.build(joinPrefix(router.keyPrefix(), route.prefix()), projectId, scene, fileName);
    }

    
    private com.sixbean.testpike.common.storage.StorageProvider providerForExistingKey(String objectKey) {
        OptionalLong taskId = projectStorageLookup.parseTaskId(objectKey);
        if (taskId.isPresent()) {
            return routeForTask(taskId.getAsLong()).provider();
        }
        OptionalLong projectId = projectStorageLookup.parseProjectId(objectKey);
        if (projectId.isPresent()) {
            return routeForProject(projectId.getAsLong()).provider();
        }
        OptionalLong baselineProjectId = projectStorageLookup.parseVisualBaselineProjectId(objectKey);
        if (baselineProjectId.isPresent()) {
            return routeForProject(baselineProjectId.getAsLong()).provider();
        }
        OptionalLong datasetProjectId = projectStorageLookup.parsePerfDatasetProjectId(objectKey);
        if (datasetProjectId.isPresent()) {
            return routeForProject(datasetProjectId.getAsLong()).provider();
        }
        OptionalLong perfArtifactProjectId = projectStorageLookup.parsePerfArtifactProjectId(objectKey);
        if (perfArtifactProjectId.isPresent()) {
            return routeForProject(perfArtifactProjectId.getAsLong()).provider();
        }
        return router.systemProvider();
    }

    
    private StorageRoute routeForTask(long taskId) {
        Optional<ProjectStorageSetting> setting = projectStorageLookup.findByTaskId(taskId);
        if (setting.isEmpty()) {
            return router.route(null, null);
        }
        return router.route(setting.get().storageMode(), setting.get().recordingPath());
    }

    
    private StorageRoute routeForProject(long projectId) {
        Optional<ProjectStorageSetting> setting = projectStorageLookup.findByProjectId(projectId);
        if (setting.isEmpty()) {
            return router.route(null, null);
        }
        return router.route(setting.get().storageMode(), setting.get().recordingPath());
    }

    
    private static String joinPrefix(String global, String projectPrefix) {
        String g = global == null ? "" : global.replaceAll("^/+|/+$", "");
        String p = projectPrefix == null ? "" : projectPrefix.replaceAll("^/+|/+$", "");
        if (g.isEmpty()) {
            return p;
        }
        return p.isEmpty() ? g : g + "/" + p;
    }
}
