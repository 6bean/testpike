package com.sixbean.testpike.core.apitest.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import com.sixbean.testpike.common.util.ApiWsContract;
import com.sixbean.testpike.common.util.ScriptSourceContract;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.FlowPromotionResultView;
import com.sixbean.testpike.core.apitest.api.dto.PromotionResultView;
import com.sixbean.testpike.core.apitest.domain.component.PromotionStepMapper;
import com.sixbean.testpike.core.apitest.domain.component.PromotionStepMapping;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.apitest.domain.service.PromotionService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Promotion chain implementation .
 * <p>Orchestration only — every legality decision reuses an existing single
 * source: assertion rows go through {@link PromotionStepMapper} , the
 * created case and the mapped script go through {@link CaseService#create} and
 * {@link ScriptService#save} (full save-gate chain, no bypass), the project
 * dimension comes from the owning collection. Repeated promotion is refused at
 * the badge read plus a conditional write-back (UPDATE ... WHERE promoted_case_id
 * IS NULL → 0 rows = concurrent promotion, roll back all) — the transaction is
 * all-or-nothing so a refused promotion leaves no orphan case.
 * <p>Debug-run history migration: the latest sandbox run verdict becomes the
 * B3 column semantics) — execution errors (status 3) and never-run requests
 * stay honest NULL/2 rather than a fabricated pass.
 */
@Service
public class PromotionServiceImpl implements PromotionService {

    /** tp_apitest_run.status domain: 1=passed 2=assert-failed 3=exec-error (0=pending, not evidence). */
    private static final int SANDBOX_RUN_PASSED = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiRequestService apiRequestService;
    private final ApiCollectionService apiCollectionService;
    private final CaseService caseService;
    private final ScriptService scriptService;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PromotionServiceImpl(ApiRequestService apiRequestService,
                                ApiCollectionService apiCollectionService,
                                CaseService caseService,
                                ScriptService scriptService,
                                NamedParameterJdbcTemplate namedJdbc) {
        this.apiRequestService = apiRequestService;
        this.apiCollectionService = apiCollectionService;
        this.caseService = caseService;
        this.scriptService = scriptService;
        this.namedJdbc = namedJdbc;
    }

    @Override
    @Audit(objectType = "apitestRequest", action = "promote", objectId = "#requestId")
    @Transactional
    public PromotionResultView promote(Long operatorId, long requestId) {
        ApiRequestInfo request = apiRequestService.get(requestId); // 40401 when missing
        if (request.promotedCaseId() != null) {
            throw new ServiceException("该请求已升格为用例 %s，无需重复升格（如需更新用例内容请在用例侧编辑）",
                    BizErrorCode.RESOURCE_CONFLICT, caseNoOfExistingPromotion(request.promotedCaseId()));
        }
        long projectId = projectIdOfRequest(request);

        // Map assertion rows through the single-source mapper (slot conflicts and
        // illegal rows refused 400 in plain words — pins the same grammar
        // the step-side save gate accepts, so mapped output passes verbatim).
        JsonNode rows = readJson(request.assertionsJson(), "断言组");
        String violation = PromotionStepMapper.mappingViolation(rows);
        if (violation != null) {
            throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
        }
        PromotionStepMapping mapping = PromotionStepMapper.toStepExpect(rows);

        ObjectNode step = buildHttpStep(request, mapping);
        boolean hasAssertions = !mapping.expect().isEmpty();

        CaseInfo created = caseService.create(operatorId, new CaseCreateInput(
                projectId, request.name(),
                "沙盒调通请求升格（GET/POST 等以请求定义为准）",
                hasAssertions ? "断言组全部满足" : "请求成功返回（源请求未配断言）",
                null, null, null, null, 3, 6, "apitest:" + requestId,
                List.of(new CaseStepInput(1, describeStep(request, hasAssertions),
                        hasAssertions ? 1 : 0)),
                null, null, "api", null, null));

        // Script via the existing save chain: envelope complete at source so the
        // manual-source full-document gate (generatedBy=4 posture — the request
        // was human-tuned, machine-mapped) validates the promotion product under
        // the same rules a hand-edited script faces. No second writing path.
        scriptService.save(new ScriptSaveInput(created.id(), "v2",
                wrapEnvelope(created, List.of(step)), ScriptSourceContract.GENERATED_BY_MANUAL, operatorId));

        // Badge write-back under the race guard (0 rows = another promotion won).
        int linked = namedJdbc.update("""
                UPDATE tp_apitest_request SET promoted_case_id = :caseId, updated_at = now()
                WHERE id = :requestId AND promoted_case_id IS NULL
                """, new MapSqlParameterSource()
                .addValue("caseId", created.id()).addValue("requestId", requestId));
        if (linked == 0) {
            throw new ServiceException("该请求已被并发升格（用例 %s），本次未重复建用例",
                    BizErrorCode.RESOURCE_CONFLICT, caseNoOfExistingPromotion(created.id()));
        }

        migrateLatestDebugRunVerdict(requestId, created.id());
        return new PromotionResultView(created.id(), created.caseNo(), requestId, projectId);
    }

    @Override
    @Audit(objectType = "apitestCollection", action = "promoteFlow", objectId = "#collectionId")
    @Transactional
    public FlowPromotionResultView promoteFlow(Long operatorId, long collectionId) {
        ApiCollectionInfo collection = apiCollectionService.get(collectionId); // 40401 when missing
        // Order single source: the exact list the run-set preview draws
        // (ApiRequestService.list, ORDER BY sort_order, id) — the flow the user
        // sees in the rail is literally the flow that gets promoted.
        List<ApiRequestInfo> members = apiRequestService.list(collectionId);
        if (members.isEmpty()) {
            throw new ServiceException("集合「" + collection.name()
                    + "」还没有请求——空流程没有可升格的内容，请先在集合内补齐请求",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Fail-closed pre-flight over the whole flow before anything is created
        // (all-or-nothing: a refusal must not leave an orphan case behind):
        // 1) already-promoted member → 409 naming the existing case (idempotent answer)
        // 2) orchestration / ws member → 400 naming the member — the 04 step
        //    vocabulary is not isomorphic (no until form, no last_status pool
        //    channel, no ws slot), pretending would promote a case that runs
        // something else .
        for (ApiRequestInfo member : members) {
            if (member.promotedCaseId() != null) {
                throw new ServiceException("请求「" + member.name() + "」已升格为用例 "
                        + caseNoOfExistingPromotion(member.promotedCaseId())
                        + "，整条流程未重复升格（如需更新用例内容请在用例侧编辑）",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
            if (!ApiWsContract.DEFAULT_PROTOCOL.equals(member.protocol())) {
                throw new ServiceException("请求「" + member.name() + "」是 "
                        + member.protocol() + " 传输——脚本步骤词表无 ws 槽位，整条流程已拒升格"
                        + "（请摘除该成员后另行升格其余链路）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (hasOrchestration(member.preconditionJson()) || hasOrchestration(member.loopJson())) {
                throw new ServiceException("请求「" + member.name()
                        + "」带编排行（前置条件/循环）——用例侧 04 词表与此不同构"
                        + "（无 until 停止形态、无 last_status 池通道），为避免升格后执行语义"
                        + "失真，整条流程已拒（请摘除编排行后再升格，或保留在接口测试中运行）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }

        // Map every member through the SAME single-source pieces the single-request
        // promotion uses (mapper judgement + step assembly + auth folding) —
        // red line 6: no second translation path for the flow form.
        List<ObjectNode> steps = new java.util.ArrayList<>(members.size());
        List<CaseStepInput> stepRows = new java.util.ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            ApiRequestInfo member = members.get(i);
            JsonNode rows = readJson(member.assertionsJson(), "断言组");
            String violation = PromotionStepMapper.mappingViolation(rows);
            if (violation != null) {
                throw new ServiceException("请求「" + member.name() + "」：" + violation,
                        BizErrorCode.VALIDATION_ERROR);
            }
            PromotionStepMapping mapping = PromotionStepMapper.toStepExpect(rows);
            steps.add(buildHttpStep(member, mapping));
            boolean hasAssertions = !mapping.expect().isEmpty();
            stepRows.add(new CaseStepInput(i + 1, describeStep(member, hasAssertions),
                    hasAssertions ? 1 : 0));
        }

        CaseInfo created = caseService.create(operatorId, new CaseCreateInput(
                collection.projectId(), collection.name(),
                "接口流程升格（按集合运行顺序，" + members.size() + " 个请求步骤）",
                "全部步骤按序执行且各自断言满足",
                null, null, null, null, 3, 6, "apitest-flow:" + collectionId,
                stepRows,
                null, null, "api", null, null));

        scriptService.save(new ScriptSaveInput(created.id(), "v2",
                wrapEnvelope(created, steps), ScriptSourceContract.GENERATED_BY_MANUAL, operatorId));

        // Badge write-back per member under the race guard (0 rows = another
        // promotion won the row — roll the whole flow back, one honest answer).
        for (ApiRequestInfo member : members) {
            int linked = namedJdbc.update("""
                    UPDATE tp_apitest_request SET promoted_case_id = :caseId, updated_at = now()
                    WHERE id = :requestId AND promoted_case_id IS NULL
                    """, new MapSqlParameterSource()
                    .addValue("caseId", created.id()).addValue("requestId", member.id()));
            if (linked == 0) {
                throw new ServiceException("请求「" + member.name() + "」已被并发升格（用例 "
                        + caseNoOfExistingPromotion(created.id()) + "），整条流程未重复建用例",
                        BizErrorCode.RESOURCE_CONFLICT);
            }
        }
        migrateFlowRunVerdict(members, created.id());
        return new FlowPromotionResultView(created.id(), created.caseNo(), collectionId,
                collection.projectId(), members.size());
    }

    /** Orchestration row presence: null/blank/{} = no gate (straight-line member). */
    private static boolean hasOrchestration(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        JsonNode node = readNodeQuietly(raw);
        return node != null && node.isObject() && !node.isEmpty();
    }

    private static JsonNode readNodeQuietly(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            // The save gate already rejected malformed rows; a broken stored value
            // is treated as presence (fail closed — refuse rather than mis-run).
            return MAPPER.createObjectNode().put("__unparseable__", raw);
        }
    }

    /**
 * Whole-flow first-trial evidence : the verdict is only
 * honest when EVERY member carries run evidence — latest run per member,
 * all passed = 1, any assert-failed/exec-error = 2 (a failure anywhere is a
 * flow failure, never fabricate a pass); a member that never ran leaves the
 * column NULL (partial evidence about a whole flow is no evidence).
 */
    private void migrateFlowRunVerdict(List<ApiRequestInfo> members, long caseId) {
        List<Long> requestIds = members.stream().map(ApiRequestInfo::id).toList();
        List<Integer> latest = namedJdbc.queryForList("""
                SELECT DISTINCT ON (request_id) status FROM tp_apitest_run
                WHERE request_id IN (:ids) AND status IN (1, 2, 3)
                ORDER BY request_id, created_at DESC, id DESC
                """, new MapSqlParameterSource("ids", requestIds), Integer.class);
        if (latest.size() < members.size()) {
            return;
        }
        int trialVerdict = latest.stream().allMatch(s -> s == SANDBOX_RUN_PASSED) ? 1 : 2;
        namedJdbc.update("UPDATE tp_script SET last_trial_result = :verdict WHERE case_id = :caseId",
                new MapSqlParameterSource().addValue("verdict", trialVerdict)
                        .addValue("caseId", caseId));
    }

    /**
     * Assemble the http.request step: method/url/path verbatim, headers array
     * reshaped to the step object map, bearer/header auth folded into headers
     * (same posture the single-run envelope dispatches via api_runner), body
     * kept JSON when parseable. Other auth types fail closed — pretending an
     * unauthenticated step equals the authenticated sandbox call would promote a
     * case that tests something else.
     */
    private ObjectNode buildHttpStep(ApiRequestInfo request, PromotionStepMapping mapping) {
        ObjectNode step = MAPPER.createObjectNode();
        step.put("action", "http.request");
        step.put("method", request.method());
        step.put("url", request.urlPath());
        ObjectNode headers = MAPPER.createObjectNode();
        JsonNode headerRows = readJson(request.headersJson(), "请求头");
        if (headerRows.isArray()) {
            for (JsonNode row : headerRows) {
                String name = row.path("name").asText("");
                if (!name.isBlank()) {
                    headers.put(name, row.path("value").asText(""));
                }
            }
        }
        foldAuthIntoHeaders(request.authJson(), headers);
        if (!headers.isEmpty()) {
            step.set("headers", headers);
        }
        String bodyText = request.bodyText() == null ? "" : request.bodyText();
        if (!bodyText.isBlank()) {
            JsonNode parsed = tryParseJson(bodyText);
            if (parsed != null && (parsed.isObject() || parsed.isArray())) {
                step.set("body", parsed);
            } else {
                step.put("body", bodyText);
            }
        }
        if (!mapping.expect().isEmpty()) {
            step.set("expect", mapping.expect());
        }
        if (mapping.saveAs() != null) {
            step.put("saveAs", mapping.saveAs());
        }
        return step;
    }

    /** Auth folding into step headers (bearer/header first phase, same shape as api_runner._auth_headers); other types are named and refused. */
    private void foldAuthIntoHeaders(String authJson, ObjectNode headers) {
        JsonNode auth = readJson(authJson, "认证配置");
        String type = auth.path("type").asText("").trim().toLowerCase(Locale.ROOT);
        if (type.isEmpty() || auth.isEmpty() || auth.isNull()) {
            return;
        }
        switch (type) {
            case "bearer" -> {
                String token = auth.path("token").asText("").trim();
                if (token.isEmpty()) {
                    throw new ServiceException("bearer 认证缺少 token——请先在请求/环境档案补齐再升格",
                            BizErrorCode.VALIDATION_ERROR);
                }
                headers.put("Authorization", "Bearer " + toStepCredDialect(token));
            }
            case "header" -> {
                String name = auth.path("name").asText("").trim();
                if (name.isEmpty()) {
                    throw new ServiceException("header 认证缺少头名（auth.name），无法合装入步骤请求头",
                            BizErrorCode.VALIDATION_ERROR);
                }
                headers.put(name, toStepCredDialect(auth.path("value").asText("")));
            }
            default -> throw new ServiceException("认证类型「" + type
                    + "」当前执行面未支持（仅 bearer / header）——为避免升格后静默降级成无认证请求，已拒",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Normalise the request-side {@code @cred/key} reference into the step-side
     * {@code {{CRED:key}}} dialect. Promoted cases run through the web/app
     * dispatch chain, whose single injector and leftover gate both speak
     * {{CRED:...}} — folding the raw @cred/ literal would slip past the gate and
     * send "Bearer @cred/tk" verbatim (fake-green auth). One conversion point
     * per dialect pair (red line 6: bearer and header slots share this helper).
     */
    private static String toStepCredDialect(String value) {
        if (value.startsWith("@cred/")) {
            return "{{CRED:" + value.substring("@cred/".length()) + "}}";
        }
        return value;
    }

    /** Step description for the case step row (human words, ≤500 by construction). */
    private String describeStep(ApiRequestInfo request, boolean hasAssertions) {
        return "调用 " + request.method() + " " + request.urlPath()
                + (hasAssertions ? " 并校验断言组（状态码/耗时/响应体/响应头/变量提取，映射见步骤动作）"
                        : "（源请求未配断言）");
    }

    /** Full 04 v2 envelope (version/caseId/env/steps) around the mapped step(s). */
    private String wrapEnvelope(CaseInfo created, List<ObjectNode> steps) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", ActionJsonValidator.ACTION_JSON_VERSION);
        root.put("caseId", created.caseNo());
        root.set("env", MAPPER.createObjectNode());
        ArrayNode stepArray = root.putArray("steps");
        steps.forEach(stepArray::add);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new ServiceException("升格步骤动作 JSON 组装失败", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Latest sandbox debug run migrates as the script first-trial evidence
     * (B3 column; first write point of last_trial_result, promotion chain).
     * Execution-error runs map to 2 (a failure is a failure — honest verdict,
     * never fabricate a pass); no runs at all leave the column NULL.
     */
    private void migrateLatestDebugRunVerdict(long requestId, long caseId) {
        List<Integer> statuses = namedJdbc.queryForList("""
                SELECT status FROM tp_apitest_run
                WHERE request_id = :requestId AND status IN (1, 2, 3)
                ORDER BY created_at DESC, id DESC LIMIT 1
                """, new MapSqlParameterSource("requestId", requestId), Integer.class);
        if (statuses.isEmpty()) {
            return;
        }
        int runStatus = statuses.get(0);
        int trialVerdict = runStatus == SANDBOX_RUN_PASSED ? 1 : 2;
        namedJdbc.update("UPDATE tp_script SET last_trial_result = :verdict WHERE case_id = :caseId",
                new MapSqlParameterSource().addValue("verdict", trialVerdict)
                        .addValue("caseId", caseId));
    }

    /** Existing-case number for the 409 echo; soft-deleted rows fall back to the id. */
    private String caseNoOfExistingPromotion(long caseId) {
        List<String> rows = namedJdbc.queryForList(
                "SELECT case_no FROM tp_test_case WHERE id = :id AND deleted = 0",
                new MapSqlParameterSource("id", caseId), String.class);
        return rows.isEmpty() ? "（原用例 #" + caseId + "）" : rows.get(0);
    }

    /** Project dimension via the owning collection (single resolution path). */
    private long projectIdOfRequest(ApiRequestInfo request) {
        ApiCollectionInfo collection = apiCollectionService.get(request.collectionId());
        return collection.projectId();
    }

    private JsonNode readJson(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ServiceException(label + "不是合法 JSON", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private JsonNode tryParseJson(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
