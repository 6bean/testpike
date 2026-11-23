package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.project.api.dto.EnvDeployInfo;
import com.sixbean.testpike.core.project.api.dto.EnvParamItem;
import com.sixbean.testpike.core.project.api.dto.EnvParamsInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

public interface ProjectEnvService {
    public List<ProjectEnvInfo> list(long projectId);

    public long create(long projectId, ProjectEnvInput input);

    public ProjectEnvInfo update(long projectId, long envId, ProjectEnvInput input);

    public void delete(long projectId, long envId);

    
    public void delete(long projectId, long envId, boolean force);

    
    public ProjectEnvInfo get(long projectId, long envId);

    
    public ProjectEnvInfo getByEnvId(long envId);

 
    public EnvDeployInfo recordDeploy(long projectId, long envId, String version, long operatorId);

    
    public EnvDeployInfo getDeployInfo(long projectId, long envId);

    
    public List<EnvParamItem> listParams(long projectId, long envId);

    
    public List<EnvParamItem> saveParams(long projectId, long envId, EnvParamsInput input);

}
