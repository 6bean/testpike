package com.sixbean.testpike.core.reportcenter.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.report.CaliberDefinition;
import com.sixbean.testpike.common.report.CaliberQuery;
import com.sixbean.testpike.common.report.CaliberService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Executes one registered caliber and assembles its payload block
 * ({display_name, version, value|rows}).
 *
 * <p>Single source for every caliber execution consumer — the snapshot builder here
 * and the program overview aggregation. A second copy of this pipeline would drift
 * the moment either side changes its block shape, so consumers assemble parameters
 * only and delegate run + block assembly to this component.
 */
@Component
public class CaliberBlockRunner {

    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper objectMapper;

    public CaliberBlockRunner(NamedParameterJdbcTemplate named, ObjectMapper objectMapper) {
        this.named = named;
        this.objectMapper = objectMapper;
    }

    /** Scalar family: one value row; NULL data = JSON null (no divide-by-zero errors). */
    public ObjectNode scalarBlock(String code, Map<String, Object> params) {
        CaliberDefinition def = CaliberService.require(code);
        CaliberQuery query = CaliberService.resolve(code, params);
        ObjectNode block = objectMapper.createObjectNode();
        block.put("display_name", def.displayName());
        block.put("version", def.version());
        BigDecimal value = named.query(query.sql(), new MapSqlParameterSource(query.params()),
                rs -> rs.next() ? rs.getBigDecimal("value") : null);
        block.putPOJO("value", value);
        return block;
    }

    /** Distribution family: value/extra rows (which codes distribute stays caller-side). */
    public ObjectNode distributionBlock(String code, Map<String, Object> params) {
        CaliberDefinition def = CaliberService.require(code);
        CaliberQuery query = CaliberService.resolve(code, params);
        ObjectNode block = objectMapper.createObjectNode();
        block.put("display_name", def.displayName());
        block.put("version", def.version());
        List<Map<String, Object>> rows = named.query(query.sql(),
                new MapSqlParameterSource(query.params()),
                (rs, n) -> Map.of("value", rs.getString("value"),
                        "extra", rs.getString("extra")));
        block.set("rows", objectMapper.valueToTree(rows));
        return block;
    }
}
