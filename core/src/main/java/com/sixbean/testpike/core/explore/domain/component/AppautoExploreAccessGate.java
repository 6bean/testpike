package com.sixbean.testpike.core.explore.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.pipeline.DenialGuidanceFrame;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ExploreEnvelopeContract;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;


@Component
public class AppautoExploreAccessGate {

 
    public static final String KEY_APPAUTO_SWITCH = "ai.explore.appauto";

    
    private static final Set<Integer> EXPLORABLE_ENV_TYPES = Set.of(1, 2);

    private final NamedParameterJdbcTemplate jdbc;
    private final SystemConfigReader configReader;
    private final ObjectMapper objectMapper;

    public AppautoExploreAccessGate(NamedParameterJdbcTemplate jdbc, SystemConfigReader configReader,
                                    ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
    }

    
    public static boolean isAppStartUrl(String startUrl) {
        return ExploreEnvelopeContract.isAppStartUrl(startUrl);
    }

    
    public void assertSessionAllowed(long projectId, String startUrl, Long envId) {
        if (!isAppStartUrl(startUrl)) {
            return;
        }
        assertSwitchOn();
        if (envId == null || envId <= 0) {
            throw new ServiceException("App 探索必须申报目标环境（仅开发/测试环境白名单可探索，"
                    + "环境缺失无从核对），请在创建表单选择环境", BizErrorCode.VALIDATION_ERROR);
        }
        if (startUrl.strip().substring(ExploreEnvelopeContract.APP_URL_PREFIX.length()).isBlank()) {
            throw new ServiceException("App 探索起点缺少被测应用包名（app:// 后须为包名，"
                    + "如 app://com.example.app）", BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> env = jdbc.queryForList(
                        "SELECT id, project_id, env_type FROM tp_project_env WHERE id = :id",
                        new MapSqlParameterSource("id", envId))
                .stream().findFirst().orElse(null);
        if (env == null || ((Number) env.get("project_id")).longValue() != projectId) {
            throw new ServiceException("App 探索目标环境不存在或不属于该项目，请重新选择环境",
                    BizErrorCode.VALIDATION_ERROR);
        }
        int envType = ((Number) env.get("env_type")).intValue();
        if (envType == 4) {
            throw new ServiceException("生产环境禁止 App 自主探索（环境白名单硬规），"
                    + "请改用「开发/测试」类型环境后重新发起", BizErrorCode.FORBIDDEN);
        }
        if (!EXPLORABLE_ENV_TYPES.contains(envType)) {
            throw new ServiceException("该环境类型（env_type=" + envType + "）不在可探索白名单"
                    + "（仅开发/测试环境），请改用测试环境", BizErrorCode.FORBIDDEN);
        }
    }

    
    public void assertActAllowed(String actionJson) {
        if (!isMobileEnvelope(actionJson)) {
            return;
        }
        assertSwitchOn();
    }

    
    private void assertSwitchOn() {
        if (!configReader.getBoolean(KEY_APPAUTO_SWITCH, false)) {
            throw new ServiceException(DenialGuidanceFrame.switchClosed("App 探索测试",
                    KEY_APPAUTO_SWITCH), BizErrorCode.FORBIDDEN);
        }
    }

    
    private boolean isMobileEnvelope(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(actionJson);
            return ExploreEnvelopeContract.isMobileEnvelope(
                    node.path("kind").asText(null), node.path("target").asText(null));
        } catch (Exception dirty) {
            return false;
        }
    }
}
