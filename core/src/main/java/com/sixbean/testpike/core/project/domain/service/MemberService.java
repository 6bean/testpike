package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.project.api.dto.MemberInfo;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface MemberService {
    public List<MemberInfo> list(long projectId);

    public void add(long projectId, long userId, int role);

    public void updateRole(long projectId, long userId, int role);

    public void remove(long projectId, long userId);

}
