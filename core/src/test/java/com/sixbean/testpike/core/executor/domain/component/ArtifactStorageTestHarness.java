package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared fixture harness for the ArtifactStorage test family (R40 single source).
 * The member classes used to declare the same Spring wiring, the same seed INSERTs
 * into tp_project/tp_exec_task and the same FK-ordered cleanup line-for-line; this
 * component is the only place that logic lives now. Every seeded row/key is registered
 * here so a failing assertion still leaves zero residue in the shared dev database.
 */
@Component
class ArtifactStorageTestHarness {

    private final ArtifactStorage artifactStorage;
    private final S3UrlExpectations urlExpectations;
    private final JdbcTemplate jdbc;
    private final ConfigService configService;
    private final List<long[]> taskHooks = new ArrayList<>();
    private final List<Long> projectHooks = new ArrayList<>();
    private final List<String> keyHooks = new ArrayList<>();

    @Autowired
    ArtifactStorageTestHarness(ArtifactStorage artifactStorage, S3UrlExpectations urlExpectations,
                               JdbcTemplate jdbc, ConfigService configService) {
        this.artifactStorage = artifactStorage;
        this.urlExpectations = urlExpectations;
        this.jdbc = jdbc;
        this.configService = configService;
    }

    ArtifactStorage artifactStorage() {
        return artifactStorage;
    }

    S3UrlExpectations urlExpectations() {
        return urlExpectations;
    }

    ConfigService configService() {
        return configService;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Seed a project row with storage mode + recording prefix; cleanup is registered here. */
    long seedProject(int storageMode, String recordingPath, String tag) {
        Long projectId = jdbc.queryForObject(
                "INSERT INTO tp_project (name, code, default_lang, storage_mode, recording_storage_path)"
                        + " VALUES (?, ?, 'zh-CN', ?, ?) RETURNING id",
                Long.class, "留证项目" + tag, "EXP-" + tag, storageMode, recordingPath);
        projectHooks.add(projectId);
        return projectId;
    }

    /** Seed project + exec task pair (the grant link needs a task carrier); returns taskId. */
    long seedTask(int storageMode, String recordingPath, String tag) {
        long projectId = seedProject(storageMode, recordingPath, tag);
        taskHooks.add(new long[]{projectId, seedTaskRow(projectId)});
        return taskHooks.get(taskHooks.size() - 1)[1];
    }

    private long seedTaskRow(long projectId) {
        Long taskId = jdbc.queryForObject(
                "INSERT INTO tp_exec_task (project_id, case_id, status) VALUES (?, 0, 1) RETURNING id",
                Long.class, projectId);
        return taskId;
    }

    /** Register an uploaded object key so cleanupAll deletes it from storage too. */
    void registerKey(String objectKey) {
        keyHooks.add(objectKey);
    }

    /** Idempotent teardown in FK order: object keys first, then tasks, then projects. */
    void cleanupAll() {
        for (String key : keyHooks) {
            artifactStorage.deleteQuietly(key);
        }
        keyHooks.clear();
        for (long[] pair : taskHooks) {
            jdbc.update("DELETE FROM tp_exec_task WHERE id = ?", pair[1]);
        }
        taskHooks.clear();
        for (Long projectId : projectHooks) {
            jdbc.update("DELETE FROM tp_project WHERE id = ?", projectId);
        }
        projectHooks.clear();
    }

    /**
     * Local-provider anti-drift check (shared by exec-artifact and explore routes): write
     * bytes under the object key on disk, assert exists/view URL/deleteQuietly all resolve
     * to the same local provider despite system default being s3, then leave no residue.
     */
    void assertLocalProviderRoundTrip(String objectKey, byte[] payload,
                                      String existsMessage, String viewUrlPrefixMessage) throws IOException {
        Path file = Path.of(configService.getValue("storage.local-root", ""), objectKey);
        Files.createDirectories(file.getParent());
        Files.write(file, payload);
        try {
            Assertions.assertTrue(artifactStorage.exists(objectKey), existsMessage);
            String view = artifactStorage.grantViewUrl(objectKey);
            Assertions.assertTrue(view.contains("/storage/files/"), viewUrlPrefixMessage + view);
            artifactStorage.deleteQuietly(objectKey);
            Assertions.assertTrue(Files.notExists(file), "deleteQuietly 须经同一 Provider 真实删除");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Random hex tag so fixture rows and object keys never collide across runs. */
    String uniq() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong(1L << 60));
    }

    /** Current yyyyMM segment the storage key convention embeds. */
    String ym() {
        return YearMonth.now().toString().replace("-", "");
    }
}
