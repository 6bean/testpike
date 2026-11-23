package com.sixbean.testpike.admin.comment.infrastructure.remote.client;

import com.sixbean.testpike.admin.comment.api.dto.CommentAttachmentView;
import com.sixbean.testpike.admin.comment.api.dto.CommentCreateRequest;
import com.sixbean.testpike.admin.comment.api.dto.CommentUnreadView;
import com.sixbean.testpike.admin.comment.api.dto.CommentView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.storage.StorageGrant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;


@Component
public class RestCoreCommentClient implements CoreCommentClient {

    private final RestClient rest;

    public RestCoreCommentClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public List<CommentView> list(String hostType, long hostId) {
        String uri = UriComponentsBuilder.fromPath("/internal/comments")
                .queryParam("hostType", hostType)
                .queryParam("hostId", hostId)
                .build().encode().toUriString();
        return RestClientInvoker.call(rest, HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public long create(long userId, CommentCreateRequest request) {
        String uri = "/internal/comments?userId=" + userId;
        Map<String, Long> result = RestClientInvoker.call(rest, HttpMethod.POST, uri, request,
                new ParameterizedTypeReference<>() {
                }, userId);
        return result == null ? 0L : result.getOrDefault("id", 0L);
    }

    @Override
    public List<CommentUnreadView> unreadCounts(long userId, String hostType, List<Long> hostIds) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/comments/unread-count")
                .queryParam("userId", userId)
                .queryParam("hostType", hostType);
        if (hostIds != null) {
            for (Long id : hostIds) {
                builder.queryParam("hostIds", id);
            }
        }
        return RestClientInvoker.call(rest, HttpMethod.GET, builder.build().encode().toUriString(),
                null, new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public void markRead(long userId, String hostType, long hostId) {
        String uri = UriComponentsBuilder.fromPath("/internal/comments/mark-read")
                .queryParam("userId", userId)
                .queryParam("hostType", hostType)
                .queryParam("hostId", hostId)
                .build().encode().toUriString();
        RestClientInvoker.call(rest, HttpMethod.POST, uri, null,
                new ParameterizedTypeReference<Response<Void>>() {
                }, userId);
    }

    @Override
    public CommentAttachmentView uploadAttachment(long commentId, String filename,
                                                  String contentType, byte[] content) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        MediaType partType = contentType == null || contentType.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(contentType);
        body.part("file", content, partType).filename(filename);
        return RestClientInvoker.callMultipart(rest,
                "/internal/comments/" + commentId + "/attachments", body,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public List<CommentAttachmentView> listAttachments(long commentId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/comments/" + commentId + "/attachments", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }

    @Override
    public StorageGrant downloadGrant(long attachmentId) {
        return RestClientInvoker.call(rest, HttpMethod.GET,
                "/internal/comments/attachments/" + attachmentId + "/download", null,
                new ParameterizedTypeReference<>() {
                }, null);
    }
}
