package com.sixbean.testpike.core.dbconnection.domain.service;

import com.sixbean.testpike.core.dbconnection.api.dto.ConnectivityTestInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInput;

import java.util.List;

public interface DbConnectionService {

    
    List<DbConnectionInfo> list(long projectId);

    
    long create(long projectId, DbConnectionInput input);

    
    DbConnectionInfo update(long projectId, long id, DbConnectionInput input);

    
    void delete(long projectId, long id);

    
    ConnectivityTestInfo test(long projectId, long id);
}
