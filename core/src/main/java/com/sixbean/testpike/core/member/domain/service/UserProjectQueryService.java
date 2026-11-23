package com.sixbean.testpike.core.member.domain.service;

import com.sixbean.testpike.core.member.api.dto.UserProjectInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

public interface UserProjectQueryService {
    
    List<UserProjectInfo> findByUserId(long userId);

    
    List<UserProjectInfo> findVisibleByUserId(long userId, boolean systemManager);
}
