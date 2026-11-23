package com.sixbean.testpike.core.executor.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * <ul>
 * </ul>
 *
 */
@Component
public class ProjectStorageLookup {

    
    static final String SCENE_EXEC_ARTIFACT = "exec-artifact";

    
    static final String SCENE_EXPLORE = "explore";

    
    static final String SCENE_VISUAL_BASELINE = "visual-baseline";

    /** perf CSV dataset scene (StorageKeys enum member; the segment before it is the projectId, read/write share one route). */
    static final String SCENE_PERF_DATASET = "perf-dataset";

    /** A-10 compiled load-artifact scene (zip bundle in object storage; segment before it is the projectId, read/write share one route). */
    static final String SCENE_PERF_ARTIFACT = "perf-artifact";

    private final NamedParameterJdbcTemplate namedJdbc;

    public ProjectStorageLookup(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }


    
    public Optional<ProjectStorageSetting> findByTaskId(long taskId) {
        return namedJdbc.query(
                """
                SELECT p.storage_mode, p.recording_storage_path
                FROM tp_exec_task t
                JOIN tp_project p ON p.id = t.project_id
                WHERE t.id = :taskId
                """,
                new MapSqlParameterSource("taskId", taskId),
                (rs, i) -> new ProjectStorageSetting(rs.getInt("storage_mode"),
                        rs.getString("recording_storage_path")))
                .stream().findFirst();
    }

    
    public Optional<ProjectStorageSetting> findByProjectId(long projectId) {
        return namedJdbc.query(
                """
                SELECT storage_mode, recording_storage_path
                FROM tp_project
                WHERE id = :projectId
                """,
                new MapSqlParameterSource("projectId", projectId),
                (rs, i) -> new ProjectStorageSetting(rs.getInt("storage_mode"),
                        rs.getString("recording_storage_path")))
                .stream().findFirst();
    }

    
    public OptionalLong parseTaskId(String objectKey) {
        return parseIdByScene(objectKey, SCENE_EXEC_ARTIFACT);
    }

    
    public OptionalLong parseProjectId(String objectKey) {
        return parseIdByScene(objectKey, SCENE_EXPLORE);
    }

    
    public OptionalLong parseVisualBaselineProjectId(String objectKey) {
        return parseIdByScene(objectKey, SCENE_VISUAL_BASELINE);
    }

    /**
 * Reverse-parse the projectId of a perf dataset key (anchored on the
 * perf-dataset scene, same segment shape as the explore/baseline anchors so
 * read and write resolve to one project route). Non-canonical keys return
 * empty and fall through to the system provider.
 */
    public OptionalLong parsePerfDatasetProjectId(String objectKey) {
        return parseIdByScene(objectKey, SCENE_PERF_DATASET);
    }

    /**
 * Reverse-parse the projectId of a A-10 perf artifact key (anchored on
 * the perf-artifact scene, same segment shape as the dataset anchor: the
 * compiled zip was written through the project route, so reads must resolve
 * back to exactly that route instead of drifting to the system provider).
 * Non-canonical keys return empty and fall through to the system provider.
 */
    public OptionalLong parsePerfArtifactProjectId(String objectKey) {
        return parseIdByScene(objectKey, SCENE_PERF_ARTIFACT);
    }

    
    private OptionalLong parseIdByScene(String objectKey, String scene) {
        if (objectKey == null || objectKey.isBlank()) {
            return OptionalLong.empty();
        }
        String[] seg = objectKey.split("/");
        for (int i = 1; i < seg.length - 2; i++) {
            if (scene.equals(seg[i])
                    && seg[i - 1].matches("\\d{1,19}")
                    && seg[i + 1].matches("\\d{6}")) {
                try {
                    return OptionalLong.of(Long.parseLong(seg[i - 1]));
                } catch (NumberFormatException e) {
                    return OptionalLong.empty();
                }
            }
        }
        return OptionalLong.empty();
    }
}
