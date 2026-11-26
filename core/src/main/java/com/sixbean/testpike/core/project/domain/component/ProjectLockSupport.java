package com.sixbean.testpike.core.project.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;


@Component
public class ProjectLockSupport {

    private final NamedParameterJdbcTemplate named;

    public ProjectLockSupport(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    
    public void lockProjectOrThrow(long projectId) {
        List<Long> locked = named.queryForList(
                "SELECT id FROM tp_project WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", projectId), Long.class);
        if (locked.isEmpty()) {
            throw new ServiceException("项目不存在（id=" + projectId
                    + "）——请到「项目管理」页确认该项目是否已被归档或删除；已归档的可由系统管理员点「归档项目」按钮后「还原」，再重新发起操作",
                    BizErrorCode.NOT_FOUND);
        }
    }
}
