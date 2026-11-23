package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs a compiled load-test artifact into one zip bundle and writes it to the
 * project-routed object storage under the {@code perf-artifact} scene, handing
 * back the OBJECT KEY as the compile handle .
 * <p>Why the move: the previous handle was an absolute path under a shared temp
 * directory ({@code java.io.tmpdir/testpike-perf-gen}). Dispatch envelopes signed
 * that path into run orders, so a recompiled run on another node (or after a
 * tmp sweep) pointed at a stale or missing file, and multi-node executors could
 * never fetch it at all. An object key is node-independent, survives sweeps, and
 * rides the same read/write route the rest of the artifact chain uses.
 * <p>Fail-closed contract: a storage write failure raises a human-worded
 * ServiceException naming the object storage — the compile NEVER silently falls
 * back to the old temp-directory handle (a fallback would re-introduce exactly
 * the stale-path bug this segment removes).
 * <p>Shared by both engine adapters (k6 and Gatling) — single implementation,
 * no per-engine copies.
 */
@Component
public class PerfArtifactBundler {

    /** Object scene reserved for compiled load artifacts (StorageKeys enum member). */
    static final String SCENE_PERF_ARTIFACT = "perf-artifact";

    private static final String ZIP_CONTENT_TYPE = "application/zip";

    private final ArtifactStorage artifactStorage;

    public PerfArtifactBundler(ArtifactStorage artifactStorage) {
        this.artifactStorage = artifactStorage;
    }

    /**
     * Bundle in-memory entries (zip-relative name → bytes) into
     * {@code <projectId>/perf-artifact/<yyyyMM>/<uuid8>-<bundleName>.zip} and
     * return the object key. The owning project routes storage exactly like the
     * dataset scene (read path reverses the project back out of the key).
     */
    public String bundleEntries(long projectId, String bundleName, Map<String, byte[]> entries) {
        byte[] zip = zipOf(entries);
        String objectKey = artifactStorage.buildProjectObjectKey(
                projectId, SCENE_PERF_ARTIFACT, bundleName + ".zip");
        try {
            artifactStorage.putBytesForProject(projectId, objectKey, zip, ZIP_CONTENT_TYPE);
        } catch (Exception storageFailure) {
            throw new ServiceException("压测编译产物写入对象存储失败（产物「" + bundleName
                    + "」未入库）：" + storageFailure.getMessage()
                    + "——请确认该项目存储配置与对象存储服务可达后重新编译场景",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return objectKey;
    }

    /**
 * Readability probe for a compile handle, dual-form :
 * a still-readable local path = legacy in-flight orders keep working
 * (compat read), anything else is treated as an object key and confirmed
 * through the same routed read path the bundle write used. Never throws —
 * callers turn false into their own human-worded refusal.
 */
    public boolean handleReadable(String handle) {
        if (handle == null || handle.isBlank()) {
            return false;
        }
        boolean localReadable;
        try {
            localReadable = Files.isReadable(Path.of(handle));
        } catch (java.nio.file.InvalidPathException notAPath) {
            localReadable = false; // Not expressible as a local path — an object key.
        }
        if (localReadable) {
            return true;
        }
        try {
            return artifactStorage.exists(handle);
        } catch (Exception routeFailure) {
            // A broken storage route reads as "not there" — the caller turns it
            // into its own human-worded refusal (fail-closed, never a false go).
            return false;
        }
    }

    /** Zip a name→bytes map, preserving entry order (manifest first aids debugging). */
    private static byte[] zipOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(buf, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        } catch (IOException zipFailure) {
            throw new ServiceException("压测编译产物打包失败：" + zipFailure.getMessage(),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return buf.toByteArray();
    }
}
