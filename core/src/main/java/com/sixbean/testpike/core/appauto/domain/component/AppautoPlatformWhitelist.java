package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;


@Component
public class AppautoPlatformWhitelist {

    private final NamedParameterJdbcTemplate jdbc;

    public AppautoPlatformWhitelist(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        Set<String> enabled = jdbc.queryForList(
                        "SELECT code FROM tp_appauto_platform WHERE status = 1",
                        new MapSqlParameterSource(), String.class)
                .stream().collect(Collectors.toSet());
        if (!enabled.contains(upper)) {
            throw new ServiceException("平台编码「" + raw.trim() + "」不在平台目录启用面（可选："
                    + String.join("、", enabled.stream().sorted().toList())
                    + "）——请改成其中任一启用编码，或在「平台目录」页先启用该平台",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return upper;
    }
}
