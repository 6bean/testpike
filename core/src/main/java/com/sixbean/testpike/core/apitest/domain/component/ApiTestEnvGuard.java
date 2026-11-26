package com.sixbean.testpike.core.apitest.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Host-whitelist precondition guard shared by single-request runs and
 * collection runs (: extraction of the check that lived inline in
 * ApiRunServiceImpl — same source, no second spectrum, SSRF red line).
 * <p>An environment profile may only be used by requests/collections of its
 * own project: the executor resolves the target host from that profile's
 * base_url whitelist, so a cross-project borrow would let one project aim
 * another project's environment.
 */
@Component
public class ApiTestEnvGuard {

    private final NamedParameterJdbcTemplate namedJdbc;

    public ApiTestEnvGuard(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    /**
     * @param envId     selected profile; null = not selected yet (allowed —
     *                  the row waits at pending and dispatch is skipped)
     * @param projectId owning project of the request/collection being run
     */
    public void requireSameProject(Long envId, Long projectId) {
        if (envId == null) {
            return;
        }
        List<Long> envProjects = namedJdbc.query(
                "SELECT project_id FROM tp_project_env WHERE id = :envId",
                new MapSqlParameterSource("envId", envId), (rs, rowNum) -> rs.getLong(1));
        if (envProjects.isEmpty() || !envProjects.get(0).equals(projectId)) {
            throw new ServiceException("所选环境不属于该请求所在项目，无法确定目标域名白名单——"
                    + "请在「环境配置」列表改选本项目下的环境后再运行",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
