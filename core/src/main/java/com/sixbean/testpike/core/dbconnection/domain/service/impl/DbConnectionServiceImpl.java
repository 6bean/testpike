package com.sixbean.testpike.core.dbconnection.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.dbconnection.api.dto.ConnectivityTestInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInfo;
import com.sixbean.testpike.core.dbconnection.api.dto.DbConnectionInput;
import com.sixbean.testpike.core.dbconnection.domain.component.ConnectivityProbe;
import com.sixbean.testpike.core.dbconnection.domain.component.DialectRegistry;
import com.sixbean.testpike.core.dbconnection.domain.service.DbConnectionService;
import com.sixbean.testpike.core.dbconnection.infrastructure.dataobject.DbConnection;
import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;


@Service
public class DbConnectionServiceImpl implements DbConnectionService {

    private static final String SELECT_COLUMNS = """
            id, project_id, env_id, name, dialect, jdbc_url, username, password_enc,
            readonly, max_pool, status, created_by, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final DialectRegistry dialectRegistry;
    private final ProdBlacklistGuard prodBlacklistGuard;
    private final ConfigCipher configCipher;
    private final ConnectivityProbe connectivityProbe;
    
    private final com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder
            batchRuntimeChangeRecorder;

    public DbConnectionServiceImpl(NamedParameterJdbcTemplate jdbc, DialectRegistry dialectRegistry,
                                   ProdBlacklistGuard prodBlacklistGuard, ConfigCipher configCipher,
                                   ConnectivityProbe connectivityProbe,
                                   com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder
                                           batchRuntimeChangeRecorder) {
        this.jdbc = jdbc;
        this.dialectRegistry = dialectRegistry;
        this.prodBlacklistGuard = prodBlacklistGuard;
        this.configCipher = configCipher;
        this.connectivityProbe = connectivityProbe;
        this.batchRuntimeChangeRecorder = batchRuntimeChangeRecorder;
    }

    @Override
    public List<DbConnectionInfo> list(long projectId) {
        return findDo("project_id = :pid", projectId).stream().map(this::toInfo).toList();
    }

    @Override
    @Audit(objectType = "db_connection", action = "create", objectId = "#result ?: #input.name()")
    public long create(long projectId, DbConnectionInput input) {
        validateChain(projectId, input);
        if (ConfigCipher.MASK.equals(input.password())) {
            throw new ServiceException("密码不能提交打码占位值，请输入真实密码后保存", BizErrorCode.VALIDATION_ERROR);
        }
        requireCipherEnabled();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("eid", input.envId())
                .addValue("name", input.name().trim())
                .addValue("dialect", input.dialect().trim().toLowerCase())
                .addValue("url", input.jdbcUrl().trim())
                .addValue("user", input.username().trim())
                .addValue("enc", configCipher.encrypt(input.password()))
                .addValue("readonly", input.readonly())
                .addValue("pool", normalizePool(input.maxPool()))
                .addValue("by", OperatorContext.currentOperatorId());
        try {
            return jdbc.queryForObject("INSERT INTO tp_db_connection "
                    + "(project_id, env_id, name, dialect, jdbc_url, username, password_enc, readonly, max_pool, created_by) "
                    + "VALUES (:pid, :eid, :name, :dialect, :url, :user, :enc, :readonly, :pool, :by) RETURNING id",
                    params, Long.class);
        } catch (DuplicateKeyException e) {
            throw conflict(input.name().trim());
        }
    }

    @Override
    @Audit(objectType = "db_connection", action = "update", objectId = "#id")
    public DbConnectionInfo update(long projectId, long id, DbConnectionInput input) {
        DbConnection current = requireDo(projectId, id);
        validateChain(projectId, input);
        String encToStore;
        if (ConfigCipher.MASK.equals(input.password())) {
            encToStore = current.getPasswordEnc();
        } else {
            requireCipherEnabled();
            encToStore = configCipher.encrypt(input.password());
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("pid", projectId)
                .addValue("eid", input.envId())
                .addValue("name", input.name().trim())
                .addValue("dialect", input.dialect().trim().toLowerCase())
                .addValue("url", input.jdbcUrl().trim())
                .addValue("user", input.username().trim())
                .addValue("enc", encToStore)
                .addValue("readonly", input.readonly())
                .addValue("pool", normalizePool(input.maxPool()));
        try {
            int updated = jdbc.update("UPDATE tp_db_connection SET env_id = :eid, name = :name, "
                    + "dialect = :dialect, jdbc_url = :url, username = :user, password_enc = :enc, "
                    + "readonly = :readonly, max_pool = :pool, updated_at = now() "
                    + "WHERE id = :id AND project_id = :pid", params);
            if (updated == 0) {
                throw new ServiceException("连接不存在或不属于该项目", BizErrorCode.NOT_FOUND);
            }
        } catch (DuplicateKeyException e) {
            throw conflict(input.name().trim());
        }
        batchRuntimeChangeRecorder.recordEnvChange(input.envId(),
                com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder.KIND_CONNECTION);
        return toInfo(requireDo(projectId, id));
    }

    @Override
    @Audit(objectType = "db_connection", action = "delete", objectId = "#id")
    public void delete(long projectId, long id) {
        requireDo(projectId, id);
        jdbc.update("DELETE FROM tp_db_connection WHERE id = :id AND project_id = :pid",
                new MapSqlParameterSource("id", id).addValue("pid", projectId));
    }

    @Override
    public ConnectivityTestInfo test(long projectId, long id) {
        DbConnection conn = requireDo(projectId, id);
        DialectRegistry.Dialect dialect = dialectRegistry.require(conn.getDialect());
        return connectivityProbe.probe(dialect.driverClass(), conn.getJdbcUrl(),
                conn.getUsername(), configCipher.decrypt(conn.getPasswordEnc()));
    }

 
    private void validateChain(long projectId, DbConnectionInput input) {
        List<Integer> envTypes = jdbc.queryForList(
                "SELECT env_type FROM tp_project_env WHERE id = :eid AND project_id = :pid",
                new MapSqlParameterSource("eid", input.envId()).addValue("pid", projectId),
                Integer.class);
        if (envTypes.isEmpty()) {
            throw new ServiceException("所选环境不存在或不属于该项目", BizErrorCode.FORBIDDEN);
        }
        if (envTypes.get(0) == 4) {
            throw new ServiceException("禁止为生产环境配置数据库连接（生产防护），请在测试/预发环境配置",
                    BizErrorCode.FORBIDDEN);
        }
        dialectRegistry.require(input.dialect());
        dialectRegistry.assertUrlMatches(input.dialect(), input.jdbcUrl());
        if (prodBlacklistGuard.matches(withoutJdbcPrefix(input.jdbcUrl()))) {
            throw new ServiceException("JDBC 主机命中生产域名黑名单，拒绝保存；黑名单配置项=TESTPIKE_PROD_BLACKLIST",
                    BizErrorCode.FORBIDDEN);
        }
        if (input.name() == null || input.name().isBlank() || input.name().trim().length() > 100) {
            throw new ServiceException("连接名不能为空且不超过 100 字", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.username() == null || input.username().isBlank()) {
            throw new ServiceException("数据库账号不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.password() == null || (input.password().isBlank() && !ConfigCipher.MASK.equals(input.password()))) {
            throw new ServiceException("数据库密码不能为空", BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private static String withoutJdbcPrefix(String jdbcUrl) {
        String trimmed = jdbcUrl.trim();
        return trimmed.regionMatches(true, 0, "jdbc:", 0, 5) ? trimmed.substring(5) : trimmed;
    }

    
    private void requireCipherEnabled() {
        if (!configCipher.isEnabled()) {
            throw new ServiceException("系统未配置 CONFIG_AES_KEY，无法加密保存连接密码，"
                    + "请先在部署环境配置该密钥后重试", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static ServiceException conflict(String name) {
        return new ServiceException("该项目已存在同名连接「" + name + "」，请更换连接名",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    private static int normalizePool(Integer maxPool) {
        int pool = maxPool == null ? 2 : maxPool;
        if (pool < 1 || pool > 5) {
            throw new ServiceException("连接池上限仅支持 1-5", BizErrorCode.VALIDATION_ERROR);
        }
        return pool;
    }

    private List<DbConnection> findDo(String whereClause, long projectId) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_db_connection WHERE " + whereClause
                + " ORDER BY id", new MapSqlParameterSource("pid", projectId),
                new BeanPropertyRowMapper<>(DbConnection.class));
    }

    private DbConnection requireDo(long projectId, long id) {
        List<DbConnection> rows = jdbc.query("SELECT " + SELECT_COLUMNS
                + " FROM tp_db_connection WHERE id = :id AND project_id = :pid",
                new MapSqlParameterSource("id", id).addValue("pid", projectId),
                new BeanPropertyRowMapper<>(DbConnection.class));
        if (rows.isEmpty()) {
            throw new ServiceException("连接不存在或不属于该项目", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private DbConnectionInfo toInfo(DbConnection d) {
        return new DbConnectionInfo(d.getId(), d.getProjectId(), d.getEnvId(), d.getName(),
                d.getDialect(), d.getJdbcUrl(), d.getUsername(), ConfigCipher.MASK,
                d.isReadonly(), d.getMaxPool(), d.getStatus(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
