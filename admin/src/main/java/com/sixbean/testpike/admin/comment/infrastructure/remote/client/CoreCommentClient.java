package com.sixbean.testpike.admin.comment.infrastructure.remote.client;

import com.sixbean.testpike.admin.comment.api.dto.CommentAttachmentView;
import com.sixbean.testpike.admin.comment.api.dto.CommentCreateRequest;
import com.sixbean.testpike.admin.comment.api.dto.CommentUnreadView;
import com.sixbean.testpike.admin.comment.api.dto.CommentView;
import com.sixbean.testpike.common.storage.StorageGrant;

import java.util.List;


public interface CoreCommentClient {

    
    List<CommentView> list(String hostType, long hostId);

    
    long create(long userId, CommentCreateRequest request);

    
    List<CommentUnreadView> unreadCounts(long userId, String hostType, List<Long> hostIds);

    
    void markRead(long userId, String hostType, long hostId);

    
    CommentAttachmentView uploadAttachment(long commentId, String filename, String contentType,
                                           byte[] content);

    
    List<CommentAttachmentView> listAttachments(long commentId);

    
    StorageGrant downloadGrant(long attachmentId);
}
