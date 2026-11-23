package com.sixbean.testpike.core.audit.domain.service;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.component.AuditHashChain;
import com.sixbean.testpike.core.audit.infrastructure.dataobject.AuditLog;
import com.sixbean.testpike.core.common.clientobject.CursorPage;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface AuditLogService {
    public void record(AuditLogInput input);

    public PageResult<AuditLog> query(Long operatorId, Long projectId, String objectType,
                                      String objectId, String action, int page, int size);

    /**
     * Page query with an optional server-side sort pair: a null/blank key keeps
     * the historical id DESC newest-first order, a whitelisted key orders the
     * whole window by that physical column with a constant id DESC tie-break so
     * paging stays deterministic under equal values, an off-list key is refused
     * fail-closed by the shared sort-column gate.
     * <p>The 7-argument overload above delegates with nulls (zero drift).
     */
    public PageResult<AuditLog> query(Long operatorId, Long projectId, String objectType,
                                      String objectId, String action, int page, int size,
                                      String sortBy, String sortDir);

    
    public CursorPage<AuditLog> queryCursor(Long operatorId, Long projectId, String objectType,
                                            String objectId, String action, Long afterId, int size);

    public boolean verifyChain();

}
