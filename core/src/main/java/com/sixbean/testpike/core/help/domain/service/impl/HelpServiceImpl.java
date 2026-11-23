package com.sixbean.testpike.core.help.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.help.api.dto.HelpDocInfo;
import com.sixbean.testpike.core.help.domain.service.HelpService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class HelpServiceImpl implements HelpService {

    
    static final String VERSION_KEY = "help.doc-version";

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCacheHolder configCache;

    public HelpServiceImpl(JdbcTemplate jdbcTemplate, ConfigCacheHolder configCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.configCache = configCache;
    }

    @Override
    public List<HelpDocInfo> list() {
        return jdbcTemplate.query("""
                SELECT id, domain_code, title, CAST(NULL AS TEXT) AS body_md, sort_no, version,
                       to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
                FROM tp_help_doc
                ORDER BY domain_code, sort_no
                """, (rs, n) -> map(rs, false));
    }

    @Override
    public List<HelpDocInfo> detail(String domainCode) {
        String code = domainCode == null ? "" : domainCode.strip();
        if (code.isEmpty()) {
            throw new ServiceException("帮助文档域码不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        return jdbcTemplate.query("""
                SELECT id, domain_code, title, body_md, sort_no, version,
                       to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at
                FROM tp_help_doc
                WHERE domain_code = ?
                ORDER BY sort_no
                """, (rs, n) -> map(rs, true), code);
    }

    @Override
    public String refreshVersion(String version) {
        String value = version == null ? "" : version.strip();
        if (value.isEmpty()) {
            throw new ServiceException("帮助文档版本号不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        int updated = jdbcTemplate.update(
                "UPDATE tp_sys_config SET config_value = ?, updated_at = now() WHERE config_key = ?",
                value, VERSION_KEY);
        if (updated == 0) {
            throw new ServiceException("帮助文档版本键缺失（V88 种子未落库）", BizErrorCode.NOT_FOUND);
        }
        configCache.put(VERSION_KEY, value);
        return value;
    }

    
    private static HelpDocInfo map(java.sql.ResultSet rs, boolean withBody) throws java.sql.SQLException {
        return new HelpDocInfo(rs.getLong("id"), rs.getString("domain_code"), rs.getString("title"),
                withBody ? rs.getString("body_md") : null,
                rs.getInt("sort_no"), rs.getString("version"), rs.getString("updated_at"));
    }
}
