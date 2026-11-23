package com.sixbean.testpike.admin.comment.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.comment.api.dto.CommentAttachmentView;
import com.sixbean.testpike.admin.comment.api.dto.CommentCreateRequest;
import com.sixbean.testpike.admin.comment.api.dto.CommentUnreadView;
import com.sixbean.testpike.admin.comment.api.dto.CommentView;
import com.sixbean.testpike.admin.comment.infrastructure.remote.client.CoreCommentClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.storage.StorageGrant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/v1/comments")
public class CommentController {

    private final CoreCommentClient coreCommentClient;

    public CommentController(CoreCommentClient coreCommentClient) {
        this.coreCommentClient = coreCommentClient;
    }

    
    @GetMapping
    @RequirePermission("perm:comment:use")
    public Response<List<CommentView>> list(@RequestParam String hostType,
                                            @RequestParam long hostId) {
        return Response.builderSuccess(coreCommentClient.list(hostType, hostId));
    }

    
    @PostMapping
    @RequirePermission("perm:comment:use")
    public Response<Map<String, Long>> create(@RequestBody CommentCreateRequest request) {
        long id = coreCommentClient.create(UserContext.currentUserId(), request);
        return Response.builderSuccess(Map.of("id", id));
    }

    
    @GetMapping("/unread-count")
    @RequirePermission("perm:comment:use")
    public Response<List<CommentUnreadView>> unreadCounts(@RequestParam String hostType,
                                                          @RequestParam List<Long> hostIds) {
        return Response.builderSuccess(
                coreCommentClient.unreadCounts(UserContext.currentUserId(), hostType, hostIds));
    }

    
    @PostMapping("/mark-read")
    @RequirePermission("perm:comment:use")
    public Response<Void> markRead(@RequestParam String hostType, @RequestParam long hostId) {
        coreCommentClient.markRead(UserContext.currentUserId(), hostType, hostId);
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/{commentId}/attachments")
    @RequirePermission("perm:comment:use")
    public Response<CommentAttachmentView> upload(@PathVariable long commentId,
                                                  @RequestPart("file") MultipartFile file)
            throws IOException {
        return Response.builderSuccess(coreCommentClient.uploadAttachment(commentId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    
    @GetMapping("/{commentId}/attachments")
    @RequirePermission("perm:comment:use")
    public Response<List<CommentAttachmentView>> listAttachments(@PathVariable long commentId) {
        return Response.builderSuccess(coreCommentClient.listAttachments(commentId));
    }

    
    @GetMapping("/attachments/{attachmentId}/download")
    @RequirePermission("perm:comment:use")
    public Response<StorageGrant> download(@PathVariable long attachmentId) {
        return Response.builderSuccess(coreCommentClient.downloadGrant(attachmentId));
    }
}
