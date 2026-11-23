package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoEngineUpdateInput;
import com.sixbean.testpike.core.appauto.domain.component.AppautoCatalogCodec;
import com.sixbean.testpike.core.appauto.domain.component.AppautoCatalogStatusRules;
import com.sixbean.testpike.core.appauto.domain.service.AppautoEngineService;
import com.sixbean.testpike.core.appauto.infrastructure.dataobject.AppautoEngine;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class AppautoEngineServiceImpl implements AppautoEngineService {

    private static final String SELECT_COLUMNS = """
            id, code, name, platform_codes::text AS platform_codes_json,
            device_kind_codes::text AS device_kind_codes_json, default_priority,
            runtime_type, runtime_version_req::text AS runtime_version_req_json,
            capabilities::text AS capabilities_json,
            driver_requirements::text AS driver_requirements_json,
            env_check_spec::text AS env_check_spec_json,
            install_recipe::text AS install_recipe_json,
            status, install_doc_url, license, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppautoCatalogCodec codec;
    private final AppautoCatalogStatusRules statusRules;

    public AppautoEngineServiceImpl(NamedParameterJdbcTemplate jdbc, AppautoCatalogCodec codec,
                                    AppautoCatalogStatusRules statusRules) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.statusRules = statusRules;
    }

    @Override
    public List<AppautoEngineInfo> listEngines(boolean includeDisabled) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM tp_appauto_engine "
                + (includeDisabled ? "" : "WHERE status = 1 ")
                + "ORDER BY default_priority, code";
        return jdbc.query(sql, new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(AppautoEngine.class))
                .stream().map(this::toView).toList();
    }

    @Override
    public AppautoEngineInfo getByCode(String code) {
        return toView(findOr404(code));
    }

    @Override
    @Audit(objectType = "appauto_engine", action = "update", objectId = "#code")
    public void update(String code, AppautoEngineUpdateInput input) {
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("引擎展示名称必填（目录与建单下拉按名展示）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        statusRules.requireValidStatus(input.status());
        findOr404(code);
        jdbc.update("UPDATE tp_appauto_engine SET name = :name, default_priority = :priority, "
                        + "install_doc_url = :doc, status = :status, updated_at = now() WHERE code = :code",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("name", input.name().trim())
                        .addValue("priority", input.defaultPriority())
                        .addValue("doc", trimToNull(input.installDocUrl()))
                        .addValue("status", input.status()));
    }

    private AppautoEngine findOr404(String code) {
        List<AppautoEngine> rows = jdbc.query("SELECT " + SELECT_COLUMNS
                        + " FROM tp_appauto_engine WHERE code = :code",
                new MapSqlParameterSource("code", code),
                new BeanPropertyRowMapper<>(AppautoEngine.class));
        if (rows.isEmpty()) {
            throw new ServiceException("引擎描述符「" + code
                    + "」不存在——请从引擎目录选择有效引擎编码", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AppautoEngineInfo toView(AppautoEngine e) {
        return new AppautoEngineInfo(e.getId(), e.getCode(), e.getName(),
                codec.readStringList(e.getPlatformCodesJson()),
                codec.readStringList(e.getDeviceKindCodesJson()),
                e.getDefaultPriority(), e.getRuntimeType(),
                codec.readStringMap(e.getRuntimeVersionReqJson()),
                codec.readBoolMap(e.getCapabilitiesJson()),
                codec.readStringList(e.getDriverRequirementsJson()),
                codec.readMapList(e.getEnvCheckSpecJson()),
                codec.readObjectMap(e.getInstallRecipeJson()),
                e.getStatus(), e.getInstallDocUrl(), e.getLicense(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
