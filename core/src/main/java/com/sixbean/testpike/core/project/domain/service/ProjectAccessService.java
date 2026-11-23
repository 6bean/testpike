package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

public interface ProjectAccessService {
    public int getMemberRole(long projectId, long userId);

    public void requireProjectMember(long projectId, long userId);

    public void requireProjectOwner(long projectId, long userId);

}
