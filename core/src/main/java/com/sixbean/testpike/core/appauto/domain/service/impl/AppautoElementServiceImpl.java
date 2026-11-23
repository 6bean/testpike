package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoElementService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Element library CRUD on NamedParameterJdbcTemplate (baseline data access
 * posture of the appauto domain).
 * <p>Validation is archive-shape only: locator state membership, confidence
 * range, name presence/length, locator config non-empty object and variant
 * items carrying resolution + artifactKey (camelCase wire keys, single-source
 * with the admin DTO and the element-library form). Runtime locator resolution stays
 * on the executor side (driver/element_candidates.py single source, never
 * mirrored here).
 * <p>Per-project unique name comes from uq_appauto_element_name; a duplicate
 * insert is translated into a human conflict message instead of a raw DB
 * error. Missing rows surface as human 404 on read/update/delete.
 */
@Service
public class AppautoElementServiceImpl implements AppautoElementService {

    private static final Set<String> LOCATOR_TYPES =
            Set.of(TYPE_IMAGE, TYPE_COORD, TYPE_REGION, TYPE_OCR);
    private static final int NAME_MAX_LEN = 200;
    private static final int PAGE_SIZE_MAX = 200;

    private static final String SELECT_COLUMNS = """
            id, project_id, name, locator_type,
            locator_config::text AS locator_config,
            variants::text AS variants,
            confidence_threshold, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AppautoElementServiceImpl(NamedParameterJdbcTemplate jdbc,
                                     ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public long create(long projectId, AppautoElementInput input) {
        MapSqlParameterSource params = validate(input)
                .addValue("pid", projectId);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO tp_appauto_element
                        (project_id, name, locator_type, locator_config,
                         variants, confidence_threshold)
                    VALUES (:pid, :name, :locatorType, :config::jsonb,
                            :variants::jsonb, :confidence)
                    RETURNING id
                    """, params, Long.class);
            return id == null ? 0L : id;
        } catch (DuplicateKeyException e) {
            throw conflict(input.name());
        }
    }

    @Override
    @Audit(objectType = "appauto_element", action = "update", objectId = "#elementId")
    public AppautoElementView update(long elementId, AppautoElementInput input) {
        require(elementId);
        MapSqlParameterSource params = validate(input).addValue("id", elementId);
        try {
            jdbc.update("""
                    UPDATE tp_appauto_element
                    SET name = :name, locator_type = :locatorType,
                        locator_config = :config::jsonb, variants = :variants::jsonb,
                        confidence_threshold = :confidence, updated_at = now()
                    WHERE id = :id
                    """, params);
        } catch (DuplicateKeyException e) {
            throw conflict(input.name());
        }
        return require(elementId);
    }

    @Override
    @Audit(objectType = "appauto_element", action = "delete", objectId = "#elementId")
    public void delete(long elementId) {
        require(elementId);
        jdbc.update("DELETE FROM tp_appauto_element WHERE id = :id",
                new MapSqlParameterSource("id", elementId));
    }

    @Override
    public AppautoElementView find(long elementId) {
        return require(elementId);
    }

    @Override
    public AppautoElementPageView page(long projectId, String keyword, String locatorType,
                                       int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), PAGE_SIZE_MAX);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("limit", safeSize)
                .addValue("off", (long) (safePage - 1) * safeSize);
        StringBuilder where = new StringBuilder("WHERE project_id = :pid ");
        if (keyword != null && !keyword.isBlank()) {
            where.append("AND name ILIKE :kw ");
            params.addValue("kw", "%" + keyword.trim() + "%");
        }
        if (locatorType != null && !locatorType.isBlank()) {
            where.append("AND locator_type = :locatorType ");
            params.addValue("locatorType", normalizeType(locatorType));
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM tp_appauto_element " + where, params, Long.class);
        List<AppautoElementView> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS
                + "FROM tp_appauto_element " + where
                + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :off", params)
                .stream().map(AppautoElementServiceImpl::toView).toList();
        return new AppautoElementPageView(rows, total == null ? 0L : total);
    }

    // ==================== private helpers ====================

    /** Archive-shape validation single point; failures carry human messages. */
    private MapSqlParameterSource validate(AppautoElementInput input) {
        if (input == null) {
            throw invalid("元素档案不能为空");
        }
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty()) {
            throw invalid("元素名称不能为空");
        }
        if (name.length() > NAME_MAX_LEN) {
            throw invalid("元素名称过长（上限 " + NAME_MAX_LEN + " 字符）");
        }
        String type = normalizeType(input.locatorType());
        if (!LOCATOR_TYPES.contains(type)) {
            throw invalid("元素定位类型只能是 IMAGE/COORD/REGION/OCR 四态之一，收到："
                    + input.locatorType());
        }
        Double confidence = input.confidenceThreshold();
        if (confidence == null || confidence < 0 || confidence > 1) {
            throw invalid("置信阈值须在 0 到 1 之间（当前：" + confidence + "）");
        }
        if (input.locatorConfig() == null || input.locatorConfig().isEmpty()) {
            throw invalid("定位参数（locator_config）须为非空对象");
        }
        List<Map<String, Object>> variants =
                input.variants() == null ? List.of() : input.variants();
        for (Map<String, Object> variant : variants) {
            if (variant == null || isBlankValue(variant.get("resolution"))
                    || isBlankValue(variant.get("artifactKey"))) {
                throw invalid("分辨率变体须同时填写「分辨率」与「图片归档号」");
            }
        }
        try {
            return new MapSqlParameterSource()
                    .addValue("name", name)
                    .addValue("locatorType", type)
                    .addValue("config", objectMapper.writeValueAsString(input.locatorConfig()))
                    .addValue("variants", objectMapper.writeValueAsString(variants))
                    .addValue("confidence", confidence);
        } catch (Exception e) {
            throw invalid("元素档案序列化失败：" + e.getMessage());
        }
    }

    private String normalizeType(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    private static boolean isBlankValue(Object raw) {
        return raw == null || String.valueOf(raw).isBlank();
    }

    private AppautoElementView require(long elementId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS
                + "FROM tp_appauto_element WHERE id = :id",
                new MapSqlParameterSource("id", elementId));
        if (rows.isEmpty()) {
            throw new ServiceException("元素 #" + elementId + " 不存在（可能已被删除）",
                    BizErrorCode.NOT_FOUND);
        }
        return toView(rows.get(0));
    }

    private ServiceException conflict(String name) {
        return new ServiceException("本项目已存在同名元素「" + name + "」，请更换名称或编辑现有元素",
                BizErrorCode.RESOURCE_CONFLICT);
    }

    private static ServiceException invalid(String reason) {
        return new ServiceException(reason + "（元素库 → 新建/编辑元素）", BizErrorCode.VALIDATION_ERROR);
    }

    private static AppautoElementView toView(Map<String, Object> row) {
        return new AppautoElementView(
                ((Number) row.get("id")).longValue(),
                row.get("project_id") == null ? null : ((Number) row.get("project_id")).longValue(),
                (String) row.get("name"),
                (String) row.get("locator_type"),
                (String) row.get("locator_config"),
                (String) row.get("variants"),
                row.get("confidence_threshold") == null ? null
                        : ((Number) row.get("confidence_threshold")).doubleValue(),
                row.get("created_at") == null ? null : String.valueOf(row.get("created_at")),
                row.get("updated_at") == null ? null : String.valueOf(row.get("updated_at")));
    }
}
