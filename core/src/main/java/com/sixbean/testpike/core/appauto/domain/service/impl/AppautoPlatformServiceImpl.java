package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoPlatformUpdateInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoCatalogCodec;
import com.sixbean.testpike.core.appauto.domain.component.AppautoCatalogStatusRules;
import com.sixbean.testpike.core.appauto.domain.service.AppautoPlatformService;
import com.sixbean.testpike.core.appauto.infrastructure.dataobject.AppautoPlatform;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class AppautoPlatformServiceImpl implements AppautoPlatformService {

    private static final String SELECT_COLUMNS = """
            id, code, name, device_kind_codes::text AS device_kind_codes_json,
            carrier_types::text AS carrier_types_json, note, status,
            created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppautoCatalogCodec codec;
    private final AppautoCatalogStatusRules statusRules;

    public AppautoPlatformServiceImpl(NamedParameterJdbcTemplate jdbc, AppautoCatalogCodec codec,
                                      AppautoCatalogStatusRules statusRules) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.statusRules = statusRules;
    }

    @Override
    public List<AppautoPlatformInfo> listPlatforms(boolean includeDisabled) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM tp_appauto_platform "
                + (includeDisabled ? "" : "WHERE status = 1 ")
                + "ORDER BY id";
        return jdbc.query(sql, new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(AppautoPlatform.class))
                .stream().map(this::toView).toList();
    }

    @Override
    public AppautoPlatformInfo getByCode(String code) {
        return toView(findOr404(code));
    }

    @Override
    @Audit(objectType = "appauto_platform", action = "update", objectId = "#code")
    public void update(String code, AppautoPlatformUpdateInput input) {
        statusRules.requireValidStatus(input.status());
        findOr404(code);
        jdbc.update("UPDATE tp_appauto_platform SET note = :note, status = :status, updated_at = now() "
                        + "WHERE code = :code",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("note", trimToNull(input.note()))
                        .addValue("status", input.status()));
    }

    private AppautoPlatform findOr404(String code) {
        List<AppautoPlatform> rows = jdbc.query("SELECT " + SELECT_COLUMNS
                        + " FROM tp_appauto_platform WHERE code = :code",
                new MapSqlParameterSource("code", code),
                new BeanPropertyRowMapper<>(AppautoPlatform.class));
        if (rows.isEmpty()) {
            throw new ServiceException("平台描述符「" + code
                    + "」不存在——请从平台目录选择有效平台编码", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AppautoPlatformInfo toView(AppautoPlatform p) {
        return new AppautoPlatformInfo(p.getId(), p.getCode(), p.getName(),
                codec.readStringList(p.getDeviceKindCodesJson()),
                codec.readStringList(p.getCarrierTypesJson()),
                p.getNote(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
