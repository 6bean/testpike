package com.sixbean.testpike.core.apitest.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplatePageView;
import com.sixbean.testpike.core.apitest.api.dto.FlowTemplateSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.component.ApiRequestSnapshotSupport;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.apitest.domain.service.FlowTemplateService;
import com.sixbean.testpike.core.apitest.infrastructure.dataobject.FlowTemplateRow;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Flow-template storage implementation ; dev DB,
 * NamedParameterJdbcTemplate baseline path, no mocks).
 * <p>save = the save-as-template instant: the collection's members are frozen in
 * the user-maintained order through the shared snapshot freezer (one shape with
 * the collection-run ledger — a template snapshot parses into the same plan
 * grammar). instantiate replays every snapshot row through the request save
 * gate, so a template can never smuggle past SSRF/orchestration/validation
 * checks and lands as ordinary editable requests.
 * <p>Provenance (source_collection_id) is traceability only: ON DELETE SET NULL
 * keeps the frozen snapshot alive after the source collection is gone — the
 * template never re-reads live rows by design.
 */
@Service
public class FlowTemplateServiceImpl implements FlowTemplateService {

    private static final BeanPropertyRowMapper<FlowTemplateRow> ROW_MAPPER =
            new BeanPropertyRowMapper<>(FlowTemplateRow.class);

    private static final String SELECT_COLUMNS = """
            SELECT t.id, t.project_id AS projectId, t.name, t.description,
                   t.source_collection_id AS sourceCollectionId,
                   t.steps_snapshot_json::text AS stepsSnapshotJson
              FROM tp_apitest_flow_template t
            """;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ApiCollectionService apiCollectionService;
    private final ApiRequestService apiRequestService;

    public FlowTemplateServiceImpl(NamedParameterJdbcTemplate namedJdbc,
                                   @Lazy ApiCollectionService apiCollectionService,
                                   @Lazy ApiRequestService apiRequestService) {
        this.namedJdbc = namedJdbc;
        this.apiCollectionService = apiCollectionService;
        this.apiRequestService = apiRequestService;
    }

    @Override
    @Transactional
    @Audit(objectType = "apitestFlowTemplate", action = "save", after = "#result")
    public FlowTemplateInfo save(Long operatorId, FlowTemplateSaveInput input) {
        if (input == null || input.collectionId() == null || input.collectionId() <= 0) {
            throw new ServiceException("流程模板必须指定来源集合", BizErrorCode.VALIDATION_ERROR);
        }
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty()) {
            throw new ServiceException("流程模板名称不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        if (name.length() > 200) {
            throw new ServiceException("流程模板名称最长 200 字符，当前 " + name.length() + " 字符",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ApiCollectionInfo collection = apiCollectionService.get(input.collectionId());
        List<ApiRequestInfo> requests = apiRequestService.list(collection.id());
        if (requests.isEmpty()) {
            throw new ServiceException("集合内没有请求，存为模板将得到空流程，请先添加请求",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String snapshot = ApiRequestSnapshotSupport.pin(requests);
        String description = input.description() == null ? "" : input.description();
        Long id = namedJdbc.queryForObject("""
                INSERT INTO tp_apitest_flow_template (project_id, name, description,
                        source_collection_id, steps_snapshot_json, created_by)
                VALUES (:projectId, :name, :description, :sourceCollectionId,
                        :snapshot::jsonb, :operatorId)
                RETURNING id""",
                new MapSqlParameterSource()
                        .addValue("projectId", collection.projectId())
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("sourceCollectionId", collection.id())
                        .addValue("snapshot", snapshot)
                        .addValue("operatorId", operatorId),
                Long.class);
        return get(id);
    }

    @Override
    public FlowTemplateInfo get(long templateId) {
        List<FlowTemplateRow> rows = namedJdbc.query(
                SELECT_COLUMNS + " WHERE t.id = :id",
                new MapSqlParameterSource("id", templateId), ROW_MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("流程模板不存在（id=" + templateId + "），可能已被删除",
                    BizErrorCode.NOT_FOUND);
        }
        return toInfo(rows.get(0));
    }

    @Override
    public FlowTemplatePageView list(long projectId, int page, int pageSize) {
        PageWindow window = PageWindow.of(page, pageSize);
        Long total = namedJdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_apitest_flow_template WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId), Long.class);
        List<FlowTemplateInfo> list = namedJdbc.query(
                SELECT_COLUMNS + " WHERE t.project_id = :projectId"
                        + " ORDER BY t.created_at DESC, t.id DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource("projectId", projectId)
                        .addValue("limit", window.size()).addValue("offset", window.offset()),
                ROW_MAPPER).stream().map(FlowTemplateServiceImpl::toInfo).toList();
        return new FlowTemplatePageView(list, total == null ? 0L : total, window.page(), window.size());
    }

    @Override
    @Transactional
    @Audit(objectType = "apitestFlowTemplate", action = "instantiate", after = "#result")
    public List<ApiRequestInfo> instantiate(Long operatorId, long templateId, long targetCollectionId) {
        if (targetCollectionId <= 0) {
            throw new ServiceException("实例化必须指定目标集合", BizErrorCode.VALIDATION_ERROR);
        }
        FlowTemplateInfo template = get(templateId);
        // Target existence verdict = the collection service's single point (404
        // passed through); project membership is guarded admin-side before the
        // bridge — core stays the validation single source per domain posture.
        apiCollectionService.get(targetCollectionId);
        JsonNode snapshot = ApiRequestSnapshotSupport.readJson(template.stepsSnapshotJson(), "[]");
        if (!snapshot.isArray() || snapshot.isEmpty()) {
            throw new ServiceException("模板快照为空或形状损坏，无法实例化（模板 id=" + templateId + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<ApiRequestInfo> created = new ArrayList<>();
        for (JsonNode row : snapshot) {
            // Every row goes through the full request save gate (name/method/
            // path SSRF/assertions/orchestration contract) — never a direct
            // table write. Snapshot keys mirror ApiRequestSnapshotSupport.pin.
            created.add(apiRequestService.create(operatorId, new RequestSaveInput(
                    targetCollectionId,
                    row.path("name").asText(""),
                    row.path("method").asText("GET"),
                    row.path("urlPath").asText(""),
                    textOrNull(row, "headersJson"),
                    row.path("bodyText").asText(""),
                    textOrNull(row, "authJson"),
                    textOrNull(row, "assertionsJson"),
                    textOrNull(row, "preconditionJson"),
                    textOrNull(row, "loopJson"),
                    row.hasNonNull("protocol") ? row.path("protocol").asText() : null)));
        }
        return created;
    }

    @Override
    @Transactional
    @Audit(objectType = "apitestFlowTemplate", action = "delete")
    public void delete(Long operatorId, long templateId) {
        // Read first so a missing id answers 404 (delete-then-count would lie 0).
        get(templateId);
        namedJdbc.update("DELETE FROM tp_apitest_flow_template WHERE id = :id",
                new MapSqlParameterSource("id", templateId));
    }

    /** jsonb sub-value → the text form the save input expects (null = absent). */
    private static String textOrNull(JsonNode row, String key) {
        JsonNode value = row.get(key);
        return value == null || value.isNull() ? null : value.toString();
    }

    private static FlowTemplateInfo toInfo(FlowTemplateRow row) {
        return new FlowTemplateInfo(row.getId(), row.getProjectId(), row.getName(),
                row.getDescription(), row.getSourceCollectionId(), row.getStepsSnapshotJson());
    }
}
