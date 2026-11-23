package com.sixbean.testpike.core.comment.domain.service;

import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.core.comment.api.dto.CommentAttachmentInfo;

import java.util.List;


public interface CommentAttachmentService {

    
    CommentAttachmentInfo upload(long commentId, String fileName, String declaredContentType, byte[] content);

    
    List<CommentAttachmentInfo> listByComment(long commentId);

    
    StorageGrant download(long attachmentId);
}
