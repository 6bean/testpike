package com.sixbean.testpike.aiservice.tool.infrastructure.remote;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolResultExternalizer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Storage-backed externalization of oversized tool results ,
 * implementing the seam the budget folder already consumes: ask core for a
 * system-level presigned PUT, upload the full text as raw UTF-8 bytes, return
 * the objectKey as the retrieval anchor written into the folded window.
 * <p>The file name embeds the owning scope ({@code task{id}-…} when a
 * generation task is present, {@code dialog-…} otherwise) so the retrieval
 * meta-tool can refuse cross-task reads from the anchor alone. The project id
 * parameter of the seam stays unused while the scene is system level — it is
 * part of the folder's forwarding contract, not a second routing source.
 * <p>Any incident (refused grant, PUT failure) propagates untouched: the
 * budget folder owns the single fail-soft point and degrades the pointer note
 * honestly, instead of this class lying about a stored anchor.
 */
@Component
public class ToolResultUploadGateway implements ToolResultExternalizer {

    private final CoreGateway coreGateway;
    private final RestClient rest;

    public ToolResultUploadGateway(CoreGateway coreGateway, RestClient.Builder restClientBuilder) {
        this.coreGateway = coreGateway;
        this.rest = restClientBuilder.build();
    }

    @Override
    public String store(String fullText, Long taskId, Long projectId) {
        String fileName = (taskId != null ? "task" + taskId : "dialog")
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + ".txt";
        ToolArtifactGrantBrief grant = coreGateway.grantToolArtifactUpload(fileName);
        rest.put()
                .uri(URI.create(grant.uploadUrl()))
                .contentType(MediaType.TEXT_PLAIN)
                .body(fullText.getBytes(StandardCharsets.UTF_8))
                .retrieve()
                .toBodilessEntity();
        return grant.objectKey();
    }
}
