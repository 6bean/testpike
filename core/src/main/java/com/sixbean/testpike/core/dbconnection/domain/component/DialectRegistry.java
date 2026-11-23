package com.sixbean.testpike.core.dbconnection.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


@Component
public class DialectRegistry {

    
    public record Dialect(String urlPrefix, String driverClass, String label) {
    }

    private static final Map<String, Dialect> DIALECTS = new LinkedHashMap<>();

    static {
        DIALECTS.put("pg", new Dialect("jdbc:postgresql:", "org.postgresql.Driver", "PostgreSQL"));
        DIALECTS.put("mysql", new Dialect("jdbc:mysql:", "com.mysql.cj.jdbc.Driver", "MySQL"));
    }

    
    public Set<String> supported() {
        return DIALECTS.keySet();
    }

    
    public Dialect require(String dialect) {
        Dialect entry = dialect == null ? null : DIALECTS.get(dialect.trim().toLowerCase());
        if (entry == null) {
            throw new ServiceException("不支持的数据库方言「" + dialect + "」，当前支持："
                    + String.join("、", DIALECTS.values().stream().map(Dialect::label).toList()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return entry;
    }

    
    public void assertUrlMatches(String dialect, String jdbcUrl) {
        Dialect entry = require(dialect);
        if (jdbcUrl == null || !jdbcUrl.trim().toLowerCase().startsWith(entry.urlPrefix())) {
            throw new ServiceException("JDBC 地址与方言 " + entry.label()
                    + " 不匹配（" + dialect + " 须以 " + entry.urlPrefix() + " 开头）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
