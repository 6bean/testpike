package com.sixbean.testpike.core.aichat.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.aichat.api.dto.ToolArtifactGrantView;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tool-result artifact upload door ; ai-service asks here before
 * PUTting the verbatim full text of an oversized tool result, so storage
 * credentials stay on the core side — same layering as the judge/explore
 * grant doors). Thin forward only: blank-name defaulting and key canonicality
 * are single-sourced in ArtifactStorage + StorageKeys.
 */
@RestController
@RequestMapping("/internal/ai-chat/tool-artifact")
public class ToolArtifactInternalController {

    private final ArtifactStorage artifactStorage;

    public ToolArtifactInternalController(ArtifactStorage artifactStorage) {
        this.artifactStorage = artifactStorage;
    }

    /** Presigned upload grant for one tool-result file name. */
    @GetMapping("/grant")
    public Response<ToolArtifactGrantView> grant(@RequestParam String fileName) {
        StorageGrant grant = artifactStorage.grantToolResultUpload(fileName);
        return Response.builderSuccess(new ToolArtifactGrantView(grant.key(), grant.url()));
    }

    /**
 * Presigned view grant for one stored tool-result key ③
 * retrieval read-back; write and read resolve on the same
 * ArtifactStorage route). Thin forward — key validity and the owner-scope
 * refusal are enforced by the ai-service retrieval meta-tool before this
 * door is ever asked.
 */
    @GetMapping("/view")
    public Response<String> view(@RequestParam String objectKey) {
        return Response.builderSuccess(artifactStorage.grantViewUrl(objectKey));
    }
}
