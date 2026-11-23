package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public interface ProjectService {
    PageResult<ProjectInfo> list(long viewerId, boolean allVisible, String keyword,
                                 Integer status, int page, int size);

    /**
 * Project list with server-side sorting : the sort pair is
 * resolved against the core column whitelist in SQL, so the ordered window
 * spans all visible projects instead of reshuffling one pageful.
 *
 * @param sortBy whitelist key (id/name/code/created_at); an off-list value
 * is refused with a human 400 naming the whitelist
 * @param sortDir asc for ascending; anything else (incl. null) means desc
 * order; a null/blank sortBy keeps the historical default
 */
    PageResult<ProjectInfo> list(long viewerId, boolean allVisible, String keyword,
                                 Integer status, int page, int size,
                                 String sortBy, String sortDir);

    public ProjectInfo get(long projectId);

    public ProjectInfo getWithViewer(long projectId, long viewerId);

    public long create(Long createdBy, ProjectCreateInput input);

    public ProjectInfo update(long projectId, ProjectUpdateInput input);

    public ProjectInfo archive(long projectId);

    
    PageResult<ProjectInfo> listArchived(int page, int size);

    
    ProjectInfo restore(long projectId);

    
    public void purge(long operatorId, long projectId, boolean force);

    public boolean exists(long projectId);

    
    List<Long> activeProjectIds();

    public int getMemberRole(long projectId, long userId);

}
