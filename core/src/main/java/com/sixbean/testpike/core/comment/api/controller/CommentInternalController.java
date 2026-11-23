package com.sixbean.testpike.core.comment.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.comment.api.dto.CommentAttachmentInfo;
import com.sixbean.testpike.core.comment.api.dto.CommentCreateInput;
import com.sixbean.testpike.core.comment.api.dto.CommentInfo;
import com.sixbean.testpike.core.comment.api.dto.CommentUnreadItem;
import com.sixbean.testpike.core.comment.domain.service.CommentAttachmentService;
import com.sixbean.testpike.core.comment.domain.service.CommentService;
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

/**
 * Internal comment interface (core service-layer migration component, called by admin over HTTP; interface-design registration is recorded).
 * <p>userId is parsed from the login state by admin and passed through (core is session-unaware, following the same pattern as FavoriteInternalController).
 *
 */
@RestController
@RequestMapping("/internal/comments")
public class CommentInternalController {

    private final CommentService commentService;
    private final CommentAttachmentService attachmentService;

    public CommentInternalController(CommentService commentService,
                                     CommentAttachmentService attachmentService) {
        this.commentService = commentService;
        this.attachmentService = attachmentService;
    }

    /** Host comment list (ascending by id). */
    @GetMapping
    public Response<List<CommentInfo>> list(@RequestParam String hostType,
                                            @RequestParam long hostId) {
        return Response.builderSuccess(commentService.list(hostType, hostId));
    }

    /** Post a comment (@mentions are included in the body; return the new comment id). */
    @PostMapping
    public Response<Map<String, Long>> create(@RequestParam long userId,
                                              @RequestBody CommentCreateInput input) {
        return Response.builderSuccess(Map.of("id", commentService.create(userId, input)));
    }

    /** Batch host unread badges (single SQL query; hosts without unread items are omitted). */
    @GetMapping("/unread-count")
    public Response<List<CommentUnreadItem>> unreadCounts(@RequestParam long userId,
                                                          @RequestParam String hostType,
                                                          @RequestParam List<Long> hostIds) {
        return Response.builderSuccess(commentService.unreadCounts(userId, hostType, hostIds));
    }

    /** Mark a host as read (idempotent upsert). */
    @PostMapping("/mark-read")
    public Response<Void> markRead(@RequestParam long userId,
                                   @RequestParam String hostType,
                                   @RequestParam long hostId) {
        commentService.markRead(userId, hostType, hostId);
        return Response.builderSuccess(null);
    }

    /** Comment attachment upload (multipart byte relay, same pattern as ToolMarket import; validation rejection means no database persistence and no object is stored). */
    @PostMapping("/{commentId}/attachments")
    public Response<CommentAttachmentInfo> uploadAttachment(@PathVariable long commentId,
                                                            @RequestPart("file") MultipartFile file)
            throws IOException {
        return Response.builderSuccess(attachmentService.upload(commentId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    /** Comment attachment list (ascending by id). */
    @GetMapping("/{commentId}/attachments")
    public Response<List<CommentAttachmentInfo>> listAttachments(@PathVariable long commentId) {
        return Response.builderSuccess(attachmentService.listByComment(commentId));
    }

    /** Attachment download presigned URL (transparent across local/s3/oss; expiration is governed by the storage.url-ttl hot-update key). */
    @GetMapping("/attachments/{attachmentId}/download")
    public Response<StorageGrant> downloadAttachment(@PathVariable long attachmentId) {
        return Response.builderSuccess(attachmentService.download(attachmentId));
    }
}
