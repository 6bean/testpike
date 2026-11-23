package com.sixbean.testpike.core.comment.domain.service;

import com.sixbean.testpike.core.comment.api.dto.CommentCreateInput;
import com.sixbean.testpike.core.comment.api.dto.CommentInfo;
import com.sixbean.testpike.core.comment.api.dto.CommentUnreadItem;

import java.util.List;


public interface CommentService {

    
    long create(long userId, CommentCreateInput input);

    
    List<CommentInfo> list(String hostType, long hostId);

    
    List<CommentUnreadItem> unreadCounts(long userId, String hostType, List<Long> hostIds);

    
    void markRead(long userId, String hostType, long hostId);
}
