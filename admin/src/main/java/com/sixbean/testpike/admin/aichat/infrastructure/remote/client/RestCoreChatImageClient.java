package com.sixbean.testpike.admin.aichat.infrastructure.remote.client;

import com.sixbean.testpike.admin.aichat.api.dto.ChatImageView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of the dialog user-image upload bridge (
 * ; comment-attachment precedent).
 * <p>Bytes pass through untouched and the part keeps the browser's original
 * declared content type — hard-coding octet-stream here would make core's
 * declared-vs-magic gate reject genuine uploads (live-proven regression in
 * the comment channel). Every business gate stays single-sourced in core.
 */
@Component
public class RestCoreChatImageClient implements CoreChatImageClient {

    private final RestClient rest;

    public RestCoreChatImageClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public ChatImageView uploadChatImage(long projectId, String filename,
                                         String contentType, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        MediaType partType = contentType == null || contentType.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType);
        body.part("file", content, partType).filename(filename);
        return RestClientInvoker.callMultipart(rest,
                "/internal/ai-chat/images?projectId=" + projectId, body,
                new ParameterizedTypeReference<>() {
                }, null);
    }
}
