package com.sixbean.testpike.core.user.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.imports.api.dto.ImportResult;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.api.dto.UserInfo;
import com.sixbean.testpike.core.user.api.dto.UserQuery;
import com.sixbean.testpike.core.user.api.dto.UserUpdateInput;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public interface UserService {
    
    public List<RoleInfo> roles();

    public long create(Long createdBy, UserCreateInput input);

    public UserInfo update(Long operatorId, long userId, UserUpdateInput input);

    public UserInfo changePassword(long userId, String oldPassword, String newPassword);

    public UserInfo delete(Long operatorId, long userId);

    public UserInfo get(long userId);

    
    public com.sixbean.testpike.core.user.api.dto.PasswordStatus passwordStatus(long userId);

    public PageResult<UserInfo> list(UserQuery query);

    public UserInfo authenticate(String username, String password);

    public boolean existsByUsername(String username);

    public boolean isEmpty();

    
    public ImportResult importUsers(Long operatorId, byte[] file, String fileName);

    
    public ImportResult retryFailedUsers(Long operatorId, byte[] file, String fileName, List<Integer> retryRows);

    
    public byte[] userTemplate();

    
    public UserInfo resetPassword(Long operatorId, long userId, String newPassword);

    
    public UserInfo updateProfile(long userId, String displayName, String avatar);

    
    public UserInfo unlock(Long operatorId, long userId);

}
