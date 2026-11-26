package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.perf.api.dto.PerfWhitelistInput;
import com.sixbean.testpike.core.perf.domain.component.PerfAccessGate;
import com.sixbean.testpike.core.perf.domain.service.PerfWhitelistService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;


@Service
public class PerfWhitelistServiceImpl implements PerfWhitelistService {

    private static final String SELECT_COLUMNS = """
            id, project_id, env_id, max_concurrency, allowed_modes::text AS allowed_modes,
            created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PerfWhitelistServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Audit(objectType = "perf_whitelist", action = "create", objectId = "#result")
    public long create(long projectId, PerfWhitelistInput input) {
        if (input.envId() == null) {
            throw new ServiceException("请选择压测目标环境；请在「压测工作台」场景向导的「本环境白名单备案」区选择环境，没有可选项时先到项目详情「环境配置」新建环境", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.maxConcurrency() == null || input.maxConcurrency() <= 0) {
            throw new ServiceException("备案压力档位上限（RPS）必须大于 0；请在「压测工作台」场景向导「本环境白名单备案」区改为正整数（示例：200）", BizErrorCode.VALIDATION_ERROR);
        }
        List<String> modes = normalizeModes(input.allowedModes());
        Long envOwn = jdbc.queryForObject("SELECT count(*) FROM tp_project_env WHERE id = :e AND project_id = :p",
                new MapSqlParameterSource("e", input.envId()).addValue("p", projectId), Long.class);
        if (envOwn == null || envOwn == 0) {
            throw new ServiceException("目标环境不存在或不属于该项目；请在项目详情「环境配置」确认环境归属本项目后重新提交", BizErrorCode.NOT_FOUND);
        }
        try {
            return jdbc.queryForObject("INSERT INTO tp_perf_whitelist "
                            + "(project_id, env_id, max_concurrency, allowed_modes, created_by) "
                            + "VALUES (:p, :e, :c, CAST(:m AS jsonb), NULL) RETURNING id",
                    new MapSqlParameterSource("p", projectId).addValue("e", input.envId())
                            .addValue("c", input.maxConcurrency()).addValue("m", writeJson(modes)),
                    Long.class);
        } catch (DuplicateKeyException dup) {
            throw new ServiceException("该环境已在压测白名单中，请直接修改压力档位上限（入口：「压测工作台」场景向导「本环境白名单备案」区重新备案该行）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    @Audit(objectType = "perf_whitelist", action = "delete", objectId = "#id")
    public void delete(long projectId, long id) {
        int deleted = jdbc.update("DELETE FROM tp_perf_whitelist WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", id).addValue("p", projectId));
        if (deleted == 0) {
            throw new ServiceException("白名单行不存在或不属于该项目；请刷新「压测工作台」场景向导的「本环境白名单备案」列表后重试，列表没有该行=已被删除", BizErrorCode.NOT_FOUND);
        }
    }

    @Override
    public List<Map<String, Object>> list(long projectId) {
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + " FROM tp_perf_whitelist "
                + "WHERE project_id = :p ORDER BY env_id", new MapSqlParameterSource("p", projectId));
    }

    @Override
    public Map<String, Object> findByEnv(long projectId, long envId) {
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + " FROM tp_perf_whitelist "
                        + "WHERE project_id = :p AND env_id = :e",
                new MapSqlParameterSource("p", projectId).addValue("e", envId))
                .stream().findFirst().orElse(null);
    }

    private List<String> normalizeModes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ServiceException("至少备案一种隔离模式（影子表/账号命名空间/流量标记）；请在「压测工作台」场景向导「本环境白名单备案」区的「隔离模式」至少勾选一种",
                    BizErrorCode.VALIDATION_ERROR);
        }
        for (String m : raw) {
            if (!PerfAccessGate.ISOLATION_MODES.contains(m)) {
                throw new ServiceException("未知隔离模式「" + m + "」，可选值="
                        + String.join("/", PerfAccessGate.ISOLATION_MODES)
                        + "；请在「压测工作台」场景向导「本环境白名单备案」的「隔离模式」中改选上述可选值", BizErrorCode.VALIDATION_ERROR);
            }
        }
        return raw.stream().distinct().sorted().toList();
    }

    private String writeJson(List<String> modes) {
        try {
            return objectMapper.writeValueAsString(modes);
        } catch (Exception impossible) {
            throw new ServiceException("隔离模式序列化失败；请在「压测工作台」场景向导「本环境白名单备案」重新提交一次，仍失败时把本提示转平台管理员", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
