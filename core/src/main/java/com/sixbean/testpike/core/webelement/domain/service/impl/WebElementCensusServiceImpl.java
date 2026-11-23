package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusReport;
import com.sixbean.testpike.core.webelement.domain.component.WebElementCensusScanner;
import com.sixbean.testpike.core.webelement.domain.service.WebElementCensusService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

/**
 * Census service door : reads the project's persisted web
 * scripts straight from tp_script and folds them through the single-source
 * WebElementCensusScanner, so the survey always ranks the real stored
 * payloads rather than caller-supplied copies.
 * <p>Scope contract, asserted by WebElementCensusServiceTest: only
 * end_type='web' cases of the requested project count; soft-deleted cases,
 * scripts still generating (status 3, payloads can be mid-write) and empty
 * payloads stay out. Ranking, page fold and anchor identity all ride the
 * scanner (R40) — this class owns the data access only.
 */
@Service
public class WebElementCensusServiceImpl implements WebElementCensusService {

    private static final String SELECT_WEB_SCRIPTS = """
            SELECT s.id, s.action_json::text AS action_json
            FROM tp_script s
            JOIN tp_test_case c ON c.id = s.case_id
            WHERE c.project_id = :projectId
              AND c.end_type = 'web'
              AND c.deleted = 0
              AND s.status <> :generatingStatus
              AND s.action_json IS NOT NULL
            ORDER BY s.id
            """;

    /** Script status 3 = still generating; payloads may be half-written. */
    private static final int SCRIPT_STATUS_GENERATING = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final WebElementCensusScanner scanner;

    public WebElementCensusServiceImpl(NamedParameterJdbcTemplate jdbc,
                                       WebElementCensusScanner scanner) {
        this.jdbc = jdbc;
        this.scanner = scanner;
    }

    @Override
    public WebElementCensusReport censusProject(Long projectId) {
        if (projectId == null || projectId <= 0) {
            throw new ServiceException("普查需要指定具体项目，请先在顶栏选择一个项目",
                    BizErrorCode.VALIDATION_ERROR);
        }
        LinkedHashMap<Long, String> actionJsonByScript = new LinkedHashMap<>();
        jdbc.query(SELECT_WEB_SCRIPTS,
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("generatingStatus", SCRIPT_STATUS_GENERATING),
                rs -> {
                    actionJsonByScript.put(rs.getLong("id"), rs.getString("action_json"));
                });
        return scanner.scan(actionJsonByScript);
    }
}
