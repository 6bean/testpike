package com.sixbean.testpike.core.perf.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.executor.domain.component.ProjectStorageLookup;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal HTTP door for perf compiled-artifact bundles ( A-10 ,
 * 03 protocol tail-follow), forwarded by the api /internal channel so the
 * out-of-system executor can materialize an object-storage handle.
 *
 * <p>Security stance mirrors the appauto package grant: the executor sends
 * only the object key from its claim envelope; this door refuses to sign a
 * presigned GET for any key that does not parse back to the perf-artifact
 * scene (guard = ProjectStorageLookup.parsePerfArtifactProjectId, the same
 * anchor the write route used — no second judgment copy). Signing itself
 * reuses ArtifactStorage.grantViewUrl (presign + urlTtl single source).
 * The returned URL is for in-memory use during the run only — never stored.
 */
@RestController
@RequestMapping("/internal/perf/artifacts")
public class PerfArtifactInternalController {

    private final ArtifactStorage artifactStorage;
    private final ProjectStorageLookup projectStorageLookup;

    public PerfArtifactInternalController(ArtifactStorage artifactStorage,
                                          ProjectStorageLookup projectStorageLookup) {
        this.artifactStorage = artifactStorage;
        this.projectStorageLookup = projectStorageLookup;
    }

    /**
     * Issue a presigned GET for one perf-artifact bundle key.
     *
     * @param body request carrying {@code objectKey} (the dispatch handle).
     * @return {@code {objectKey, downloadUrl}} — empty/malformed keys and keys
     *         outside the perf-artifact scene are refused fail-closed with a
     *         human-language error (no silent signing of arbitrary objects).
     */
    @PostMapping("/download-grant")
    public Response<Map<String, Object>> grantDownload(@RequestBody Map<String, Object> body) {
        String objectKey = body == null ? null : str(body.get("objectKey"));
        if (objectKey == null || objectKey.isBlank()) {
            throw new ServiceException("压测产物 objectKey 不能为空（下载授权按编译段回执键签发）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Guard: only keys the perf-artifact write route could have produced
        // get signed. A foreign/escalated key (exec screenshot, another
        // scene) is rejected by name instead of signed.
        if (projectStorageLookup.parsePerfArtifactProjectId(objectKey).isEmpty()) {
            throw new ServiceException("压测产物键非法：「" + objectKey
                    + "」不属于压测编译产物存储域，无法签发下载地址——请回传编译回执返回的「objectKey」原值",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("objectKey", objectKey);
        data.put("downloadUrl", artifactStorage.grantViewUrl(objectKey));
        return Response.builderSuccess(data);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
