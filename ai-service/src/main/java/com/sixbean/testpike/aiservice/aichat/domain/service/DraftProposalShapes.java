package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.WriteProposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-kind proposal shapes of the dialog draft emitter (tool-surface clearing
 * batch stage 2; split out of ChatRunOrchestrator so the hot orchestrator
 * never grows per-domain branches).
 * <p>A shape answers one question: can this argument body land through the
 * draft door AT ALL, and what does the human see on the card? Anything that
 * cannot land (unparsable JSON, a create without its name, an edit without
 * the optimistic anchor the door will re-check) yields null = honest absence —
 * a half-shape card would only bait a confirmation click that must fail.
 * <p>Kind branches live here and in the core-side write strategies, never in
 * the emitter loop: adding a domain = one branch here + one strategy bean +
 * one registry row, the orchestrator stays untouched.
 */
final class DraftProposalShapes {

    private static final Logger log = LoggerFactory.getLogger(DraftProposalShapes.class);

    private DraftProposalShapes() {
    }

    /**
 * Argument body → proposal value object (server-side assembly; the client
 * never feeds these anchors back). Unparsable JSON or an unlandable
 * half-shape yields null, logged for observability.
 *
 * <p>The session-project overload (API pass-through batch): the interface
 * change card must state which project it lands in, and the honest source
 * for that is the conversation's own server-validated segment — the same
 * truth the landing door re-stamps from. Requiring the model to echo a
 * numeric project id it was told never to supply made the card mutually
 * unsatisfiable (shape demanded it, tool description forbade it), which
 * silently killed every interface-write card. Callers without a segment keep
 * the legacy behavior (payload-named anchor required, fail-soft).
 */
    static WriteProposal toWriteProposal(String toolName, String objectType,
                                         String argumentsJson, ObjectMapper reader) {
        return toWriteProposal(toolName, objectType, argumentsJson, reader, 0L);
    }

    static WriteProposal toWriteProposal(String toolName, String objectType,
                                         String argumentsJson, ObjectMapper reader,
                                         long sessionProjectId) {
        return toWriteProposal(toolName, objectType, argumentsJson, reader,
                sessionProjectId, null);
    }

    /**
 * Piece F overload : the login-state card is the only shape that
 * asks the server which names its staged slot actually holds, so every
 * card row is rewritten onto that name domain BEFORE minting. A null
 * probe means no name domain is available — LOGIN_STATE_WRITE then
 * honestly abstains; every other kind ignores the probe entirely.
 */
    static WriteProposal toWriteProposal(String toolName, String objectType,
                                         String argumentsJson, ObjectMapper reader,
                                         long sessionProjectId,
                                         com.sixbean.testpike.aiservice.aichat.domain.component
                                                 .LoginStateStagedNames stagedNames) {
        return toWriteProposal(toolName, objectType, argumentsJson, reader, sessionProjectId,
                stagedNames, java.util.List.of());
    }

    /**
 * Live-credential form : the same mint with the staging
 * tokens THIS round's panel staging actually parked, so the login-state
 * shape can reconcile a transcribed pointer that routes to a dead slot.
 * Other kinds ignore the list entirely.
 */
    static WriteProposal toWriteProposal(String toolName, String objectType,
                                         String argumentsJson, ObjectMapper reader,
                                         long sessionProjectId,
                                         com.sixbean.testpike.aiservice.aichat.domain.component
                                                 .LoginStateStagedNames stagedNames,
                                         java.util.List<String> liveStagingTokens) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        JsonNode body;
        try {
            body = reader.readTree(argumentsJson);
        } catch (JsonProcessingException malformed) {
            log.warn("Draft argument body is not valid JSON; proposal omitted tool={} reason={}",
                    toolName, malformed.getOriginalMessage());
            return null;
        }
        return switch (objectType) {
            case "CASE_WRITE" -> caseShape(toolName, body, argumentsJson);
            case "CASE_ROLLBACK" -> rollbackShape(toolName, body, argumentsJson);
            case "TAG_WRITE" -> tagShape(toolName, body, argumentsJson);
            case "API_ACCESS_WRITE" -> apiAccessShape(toolName, body, argumentsJson, sessionProjectId);
            case "PROMPT_TPL_WRITE" -> promptTplShape(toolName, body, argumentsJson);
            case "SKILL_WRITE" -> skillShape(toolName, body, argumentsJson);
            case "CASE_REVIEW" -> reviewShape(toolName, body, argumentsJson);
            case "PLAN_WRITE" -> planShape(toolName, body, argumentsJson);
            case "BATCH_WRITE" -> batchShape(toolName, body, argumentsJson);
            case "REPORT_WRITE" -> reportShape(toolName, body, argumentsJson);
            case "APITEST_WRITE" -> apitestShape(toolName, body, argumentsJson);
            case "APISPEC_WRITE" -> apiSpecShape(toolName, body, argumentsJson);
            case "PERF_LAUNCH" -> perfLaunchShape(toolName, body, argumentsJson);
            case "RESERVATION_WRITE" -> reservationShape(toolName, body, argumentsJson);
            case "POPUP_RULE_LEARN" -> popupRuleLearnShape(toolName, body, argumentsJson);
            case "MOCK_SPACE_WRITE" -> mockSpaceShape(toolName, body, argumentsJson);
            case "MOCK_STUB_WRITE" -> mockStubShape(toolName, body, argumentsJson);
            case "AIGEN_BATCH_WRITE" -> aiGenBatchShape(toolName, body, argumentsJson);
            case "DEFECT_WRITE" -> defectShape(toolName, body, argumentsJson);
            case "DATA_FACTORY_WRITE" -> dataFactoryShape(toolName, body, argumentsJson);
            case "EXPLORE_CREATE" -> exploreCreateShape(toolName, body, argumentsJson);
            case "FLAKY_ISOLATION" -> flakyIsolationShape(toolName, body, argumentsJson);
            case "REQUIREMENT_WRITE" -> requirementShape(toolName, body, argumentsJson);
            case "LOGIN_STATE_WRITE" -> loginStateShape(toolName, body, reader, stagedNames,
                    liveStagingTokens);
            default -> {
                // A registry kind this build has no shape for must not produce
                // a card: the core ledger would journal a row no emitter-side
                // anchor check ever validated. Honest absence beats a card we
                // cannot guarantee lands.
                log.warn("Draft type {} has no emission shape yet; proposal omitted tool={}", objectType, toolName);
                yield null;
            }
        };
    }

    /** Case create/edit .
 * <p>Which shape a call proposes is decided by the tool NAME first (the
 * registry routes case.update calls even when the model forgets the
 * caseId anchor — that half-shape must stay an honest absence, exactly as
 * before generalisation), falling back to body anchors only when the name
 * carries no create/update verb. */
    private static WriteProposal caseShape(String toolName, JsonNode body, String raw) {
        long caseId = body.path("caseId").asLong(0L);
        int baseVersion = body.path("baseVersion").asInt(0);
        boolean updateNamed = toolName != null && toolName.endsWith(".update");
        boolean createNamed = toolName != null && toolName.endsWith(".create");
        if (updateNamed || (!createNamed && caseId > 0)) {
            if (caseId <= 0 || baseVersion <= 0) {
                log.warn("Case-edit draft lacks caseId/baseVersion anchors, cannot land safely; proposal omitted caseId={} baseVersion={}",
                        caseId, baseVersion);
                return null;
            }
            return new WriteProposal(toolName, "CASE_WRITE",
                    "编辑用例 #" + caseId + "：" + changedFieldList(body, "caseId", "baseVersion"),
                    caseId, baseVersion, raw);
        }
        String title = body.path("title").asText("");
        if (title.isBlank()) {
            log.warn("Case-create draft lacks a title, cannot land; proposal omitted");
            return null;
        }
        return new WriteProposal(toolName, "CASE_WRITE", "新建用例：" + title.trim(),
                0L, 0, raw);
    }

    /**
 * Case-rollback proposal . The confirmed card
 * replays the case through the shared CaseService.rollback chain, so the
 * body must echo THREE numbers: which case, which history version to
 * replay, and which live version the card was cut against. A missing
 * target version could not land (the door refuses anything below 1); a
 * missing lock anchor would always conflict at confirm time — both are
 * bait-a-failed-confirmation shapes, honestly dropped here. The raw body
 * rides through as the payload snapshot the core strategy re-parses.
 */
    private static WriteProposal rollbackShape(String toolName, JsonNode body, String raw) {
        long caseId = body.path("caseId").asLong(0L);
        int targetVersion = body.path("targetVersion").asInt(0);
        int baseVersion = body.path("baseVersion").asInt(0);
        if (caseId <= 0 || targetVersion < 1 || baseVersion <= 0) {
            log.warn("Rollback draft lacks caseId/targetVersion/baseVersion anchors, cannot land safely; proposal omitted "
                    + "caseId={} targetVersion={} baseVersion={}", caseId, targetVersion, baseVersion);
            return null;
        }
        return new WriteProposal(toolName, "CASE_ROLLBACK",
                "回滚用例 #" + caseId + " 到版本 v" + targetVersion,
                caseId, baseVersion, raw);
    }

    /**
 * Prompt-template new-version proposal . The template
 * family is append-only ("edit = new version row"), so every card is a
 * create with no pre-existing anchor. A save door would reject the row
 * for a missing name/domain/content, so a body without them gets no card
 * — the same bait-a-failed-confirmation rule as every other shape here.
 */
    private static WriteProposal promptTplShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("");
        String domain = body.path("domain").asText("");
        String content = body.path("content").asText("");
        if (name.isBlank() || domain.isBlank() || content.isBlank()) {
            log.warn("Prompt-template draft lacks required name/domain/content, cannot land; proposal omitted name={} domain={}",
                    name.isBlank() ? "" : name, domain.isBlank() ? "" : domain);
            return null;
        }
        return new WriteProposal(toolName, "PROMPT_TPL_WRITE",
                "新建提示词模板版本：「" + name.trim() + "」（域 " + domain.trim() + "）",
                0L, 0, raw);
    }

    /**
 * New-skill proposal . A skill row must carry a name, a
 * type and a body for the save door to accept it, so a payload without
 * them gets no card — the same bait-a-failed-confirmation rule as every
 * other shape here. The card is always a create: skills keep a human
 * approval chain, so the landing row stays in draft state untouched.
 */
    private static WriteProposal skillShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("");
        String skillBody = body.path("body").asText("");
        int type = body.path("type").asInt(0);
        if (name.isBlank() || skillBody.isBlank() || type <= 0) {
            log.warn("Skill draft lacks required name/type/body, cannot land; proposal omitted name={} type={}",
                    name.isBlank() ? "" : name, type);
            return null;
        }
        return new WriteProposal(toolName, "SKILL_WRITE",
                "新建技能：「" + name.trim() + "」（确认后进入待审批草稿）",
                0L, 0, raw);
    }

    /**
 * Review-decision proposal . A decision always names an
 * existing case AND its optimistic anchor: a body without caseId or
 * expectedVersion cannot land safely, so it gets no card. The action
 * code set mirrors the core review contract (pass/need_change/reject);
 * reject/need_change additionally require at least one feedback type —
 * the save door would refuse the row otherwise, and a card that must
 * fail on confirm is banned here like in every other shape.
 */
    private static WriteProposal reviewShape(String toolName, JsonNode body, String raw) {
        long caseId = body.path("caseId").asLong(0L);
        int expectedVersion = body.path("expectedVersion").asInt(0);
        String action = body.path("action").asText("");
        if (caseId <= 0 || expectedVersion <= 0 || !REVIEW_ACTION_LABELS.containsKey(action)) {
            log.warn("Review-decision draft lacks caseId/expectedVersion or the action is invalid, cannot land; proposal omitted caseId={} action={}",
                    caseId, action);
            return null;
        }
        if (!"pass".equals(action) && !body.path("feedbackTypes").isArray()) {
            log.warn("Review-decision draft ({}) lacks the issue-type list; confirmation would fail; proposal omitted caseId={}", action, caseId);
            return null;
        }
        if (!"pass".equals(action) && body.path("feedbackTypes").isEmpty()) {
            log.warn("Review-decision draft ({}) has an empty issue-type list; confirmation would fail; proposal omitted caseId={}", action, caseId);
            return null;
        }
        return new WriteProposal(toolName, "CASE_REVIEW",
                "评审用例 #" + caseId + "：判定「" + REVIEW_ACTION_LABELS.get(action) + "」",
                caseId, expectedVersion, raw);
    }

    /** Review action codes accepted by the core review door, with human labels. */
    private static final java.util.Map<String, String> REVIEW_ACTION_LABELS = java.util.Map.of(
            "pass", "通过", "need_change", "需修改", "reject", "拒绝");

    /**
 * New-plan proposal . The save door refuses a plan without
 * a name, without an environment set, or with a scope rule that names no
 * dimension (an unbounded scope would pull every case in the project), so
 * a body missing any of them gets no card — the same bait-a-failed-
 * confirmation rule as every other shape here. The card is always a
 * create: the row lands in draft state and publishing stays a deliberate
 * human click on /plans.
 */
    private static WriteProposal planShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("");
        JsonNode envIds = body.path("envIds");
        JsonNode scope = body.path("caseScope");
        if (name.isBlank() || !envIds.isArray() || envIds.isEmpty()) {
            log.warn("Plan draft lacks required name or envIds; confirmation would fail; proposal omitted name={} envIds={}",
                    name.isBlank() ? "" : name, envIds.toString());
            return null;
        }
        if (scope.isMissingNode() || scope.isNull() || isEmptyScopeRule(scope)) {
            log.warn("Plan draft scope rules carry no dimension constraint; confirmation would fail; proposal omitted name={}", name);
            return null;
        }
        return new WriteProposal(toolName, "PLAN_WRITE",
                "新建测试计划：「" + name.trim() + "」（" + envIds.size() + " 个环境，确认后进入草稿态）",
                0L, 0, raw);
    }

    /** Mirror of core CaseScopeRule.isEmptyRule(): no dimension named at all. */
    private static boolean isEmptyScopeRule(JsonNode scope) {
        return !hasItems(scope.path("phases")) && !hasItems(scope.path("priorities"))
                && scope.path("keyword").asText("").isBlank()
                && !hasItems(scope.path("tagIds")) && !hasItems(scope.path("explicitCaseIds"));
    }

    private static boolean hasItems(JsonNode arrayNode) {
        return arrayNode.isArray() && !arrayNode.isEmpty();
    }

    /**
 * New-batch proposal . The save door refuses a batch
 * without a name and refuses one that selects no case ("select at least
 * one case from the library"), so a body missing either gets no card —
 * the same bait-a-failed-confirmation rule as every other shape here.
 * The card is always a create of a DRAFT batch: starting it (the precheck
 * chain, the version snapshot and the task queue) stays a deliberate
 * human click on /batches, never a dialog card.
 */
    private static WriteProposal batchShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("");
        JsonNode caseIds = body.path("caseIds");
        if (name.isBlank() || !hasItems(caseIds)) {
            log.warn("Batch draft lacks the batch name or has no cases selected; confirmation would fail; proposal omitted name={} caseIds={}",
                    name.isBlank() ? "" : name, caseIds.toString());
            return null;
        }
        return new WriteProposal(toolName, "BATCH_WRITE",
                "新建执行批次：「" + name.trim() + "」（" + caseIds.size()
                        + " 条用例，落为草稿批，启动仍在批次页人工操作）",
                0L, 0, raw);
    }

    /**
 * New-test-report proposal . The save door refuses a
 * plan-range report with no plan picked and a time-range report with no
 * start/end window, so a body missing its own range anchor gets no card —
 * the same bait-a-failed-confirmation rule as every other shape here.
 * The card is always a create: the aggregation snapshot is computed and
 * inserted by the same chain the report page's generate dialog runs;
 * exporting and deleting reports stay deliberate human clicks on
 * /test-report, never a dialog card.
 */
    private static WriteProposal reportShape(String toolName, JsonNode body, String raw) {
        int rangeType = body.path("rangeType").asInt(0);
        String anchor;
        if (rangeType == 1) {
            long planId = body.path("planId").asLong(0L);
            if (planId <= 0) {
                log.warn("Report draft set to by-plan without a chosen plan; confirmation would fail; proposal omitted tool={}", toolName);
                return null;
            }
            anchor = "所选计划 #" + planId;
        } else if (rangeType == 2) {
            String start = body.path("startTime").asText("");
            String end = body.path("endTime").asText("");
            if (start.isBlank() || end.isBlank()) {
                log.warn("Report draft set to by-time-range without start/end dates; confirmation would fail; proposal omitted tool={}", toolName);
                return null;
            }
            anchor = "时间窗 " + start + " ~ " + end;
        } else {
            log.warn("Report draft range type invalid (1=plan 2=time range); confirmation would fail; proposal omitted tool={} rangeType={}",
                    toolName, rangeType);
            return null;
        }
        String title = body.path("title").asText("");
        return new WriteProposal(toolName, "REPORT_WRITE",
                "生成测试总结报告：" + (title.isBlank() ? "（标题将自动生成）" : "「" + title.trim() + "」")
                        + "，" + anchor + "，导出与删除仍在报告页人工操作",
                0L, 0, raw);
    }

    /**
 * Api-test collection defaults . Saving defaults is always
 * an update of an existing collection — the payload must name its
 * collection id, and a body that saves neither default headers nor a
 * default auth block has nothing the door could write, so both halves
 * missing gets no card (the same bait-a-failed-confirmation rule as every
 * other shape here). Request-level saving stays a human editor action:
 * an editor draft's live state cannot be rebuilt from a dialog payload.
 */
    private static WriteProposal apitestShape(String toolName, JsonNode body, String raw) {
        long collectionId = body.path("collectionId").asLong(0L);
        if (collectionId <= 0) {
            log.warn("API-collection default-config draft lacks the collectionId anchor; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        boolean hasHeaders = !body.path("headersJson").asText("").isBlank();
        boolean hasAuth = !body.path("authJson").asText("").isBlank();
        if (!hasHeaders && !hasAuth) {
            log.warn("API-collection default-config draft carries neither default headers nor default auth config; nothing to store; proposal omitted tool={} collectionId={}",
                    toolName, collectionId);
            return null;
        }
        String what = hasHeaders && hasAuth ? "默认请求头与默认认证配置"
                : hasHeaders ? "默认请求头" : "默认认证配置";
        return new WriteProposal(toolName, "APITEST_WRITE",
                "保存接口集 #" + collectionId + " 的" + what + "（确认后走接口集页同一保存链；名称与标签保持不变）",
                collectionId, 0, raw);
    }

    /**
 * Api-inventory contract pull through the URL entry . A
 * pull is always an append onto the project's version chain, so the
 * payload must name both the contract name and the document url — the
 * fetch chain refuses a blank either way, and a card that must fail on
 * confirmation is banned here like in every other shape. The owning
 * project is NOT part of the payload: it comes from the chat session at
 * landing time, so a model-forged url can never target another project's
 * version chain (the SSRF whitelist gate stays inside the core fetcher).
 */
    private static WriteProposal apiSpecShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("").trim();
        String url = body.path("url").asText("").trim();
        if (name.isEmpty() || url.isEmpty()) {
            log.warn("Contract-pull draft lacks name/url anchors; confirmation would fail; proposal omitted tool={} nameBlank={} urlBlank={}",
                    toolName, name.isEmpty(), url.isEmpty());
            return null;
        }
        return new WriteProposal(toolName, "APISPEC_WRITE",
                "从 URL 拉取接口契约「" + name + "」入库并挂版本链（确认后走接口清单页同一拉取链；白名单、体积与格式校验由该链完成）",
                0L, 0, raw);
    }

    /**
 * Pressure-run launch . Launching is the heaviest write
 * face the dialog can propose, so the card must state the cost up front
 * (the run occupies one environment; the production-change safety gate,
 * the duration ceiling and the environment lock still own the decision
 * inside the launch transaction — a rejection surfaces with its reason).
 * The payload only needs the scenario anchor; the owning project comes
 * from the chat session at landing time, so a model-forged scenario id
 * can never press another project's environment (the launch chain
 * re-checks the scenario belongs to the session project). A card without
 * that anchor would fail on confirmation — banned like in every other
 * shape here. Creating the scenario itself stays a human action on
 * /perf: the wizard is a multi-step design flow, not a dialog payload.
 */
    private static WriteProposal perfLaunchShape(String toolName, JsonNode body, String raw) {
        long scenarioId = body.path("scenarioId").asLong(0L);
        if (scenarioId <= 0) {
            log.warn("Pressure-run draft lacks the scenarioId anchor; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        return new WriteProposal(toolName, "PERF_LAUNCH",
                "发起压测：场景 #" + scenarioId + "（确认后走压测工作台同一发起链；本次运行将占用一个测试环境，"
                        + "生产变更安全闸、时长上限与环境执行锁照常在发起时判定，不通过会被拒绝并提示原因）",
                scenarioId, 0, raw);
    }

    /**
 * Device reservation create . A reservation holds one
 * device for a time window, so the payload must name the device and both
 * window ends — the create chain refuses a missing or inverted window
 * anyway, and a card that must fail on confirmation is banned here like
 * in every other shape. The card states the occupied device and window
 * up front (cost statement of the U-5 summary-card wording family):
 * whether the window actually conflicts is decided inside the SAME
 * create transaction at landing time, whose refusal names the current
 * holder and the expected release time. The owning project is NOT part
 * of the payload: it comes from the chat session at landing time.
 * Releasing or cancelling an existing reservation stays a human action
 * on /appauto/reservations.
 */
    private static WriteProposal reservationShape(String toolName, JsonNode body, String raw) {
        long deviceId = body.path("deviceId").asLong(0L);
        String startsAt = body.path("startsAt").asText("").trim();
        String endsAt = body.path("endsAt").asText("").trim();
        if (deviceId <= 0 || startsAt.isEmpty() || endsAt.isEmpty()) {
            log.warn("Device-reservation draft lacks deviceId/time-window anchors; confirmation would fail; proposal omitted tool={} deviceId={} window={}~{}",
                    toolName, deviceId, startsAt, endsAt);
            return null;
        }
        return new WriteProposal(toolName, "RESERVATION_WRITE",
                "预约设备 #" + deviceId + "（" + startsAt + " ~ " + endsAt
                        + "，确认后走预约日历同一创建链；本次预约将占用该设备一个时间窗，"
                        + "与该设备已有预约或租约冲突时会被拒绝并点名占用者与预计释放时间）",
                deviceId, 0, raw);
    }

    /**
 * Popup-rule self-learning . Learning reads ONE finished
 * execution task's watch frames (sensitive frames excluded inside the
 * chain) and produces a PENDING rule draft — the payload must name the
 * task, and a card that must fail on confirmation is banned here like in
 * every other shape. The card states the source task and the
 * two-layer gate up front: confirming only runs the SAME learn chain the
 * approval view's button uses (learning switch, structure gate, pending
 * status all re-decided at landing time); the rule enters the library
 * only after a human approves it on /appauto/popup-rules. targetId is 0
 * — no rule row exists when the card is emitted; the landing strategy
 * rewrites it to the produced rule id. The task's owning project is read
 * from tp_exec_task by the learn chain and compared against the chat
 * session before anything runs, so a forged task id never reaches
 * another project's frames.
 */
    private static WriteProposal popupRuleLearnShape(String toolName, JsonNode body, String raw) {
        long taskId = body.path("taskId").asLong(0L);
        if (taskId <= 0) {
            log.warn("Popup-rule-learning draft lacks the taskId anchor; confirmation would fail; proposal omitted tool={} taskId={}",
                    toolName, taskId);
            return null;
        }
        return new WriteProposal(toolName, "POPUP_RULE_LEARN",
                "从执行任务 #" + taskId + " 的监看帧学习弹窗规则（确认后走弹窗规则审批页同一学习链，"
                        + "读取该任务的非敏感帧并调用模型识别一次；产出为待批准规则草稿，"
                        + "须人工在「APP 自动化 → 弹窗规则」批准后才进入规则库，不会自动入库）",
                0, 0, raw);
    }

    /**
 * Mock-space create . The save chain refuses a space
 * without a name or a routing key (it enters the access path
 * /mocks/{spaceKey}/ and must be site-unique), so a payload missing
 * either gets no card — the same bait-a-failed-confirmation rule as
 * every other shape here. The card states the two-layer gate up front:
 * confirming only runs the SAME create chain the space page's dialog
 * uses (validator, production-environment ban and uniqueness conflict
 * re-decided at landing time), and the row lands as a DRAFT space —
 * publishing it opens the traffic door and stays a deliberate human
 * click on /mock/spaces. The owning project is NOT part of the payload:
 * it comes from the chat session at landing time, so a forged call can
 * never open a space in another project's lane. targetId is 0 — no
 * space row exists when the card is emitted; the landing strategy
 * rewrites it to the produced space id.
 */
    private static WriteProposal mockSpaceShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("").trim();
        String spaceKey = body.path("spaceKey").asText("").trim();
        if (name.isEmpty() || spaceKey.isEmpty()) {
            log.warn("Mock-space draft lacks name/spaceKey anchors; confirmation would fail; proposal omitted tool={} nameBlank={} keyBlank={}",
                    toolName, name.isEmpty(), spaceKey.isEmpty());
            return null;
        }
        return new WriteProposal(toolName, "MOCK_SPACE_WRITE",
                "新建 Mock 空间「" + name + "」（路由标识 " + spaceKey
                        + "，确认后走接口仿真 → Mock 空间页同一创建链；名称与路由标识规则、生产环境禁用与路由标识全站唯一冲突照常在创建时复核，不通过会被拒绝并提示原因；"
                        + "产出为草稿态空间，须人工在空间页发布后才对外提供访问）",
                0L, 0, raw);
    }

    private static WriteProposal mockStubShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("").trim();
        String method = body.path("method").asText("").trim();
        String pathPattern = body.path("pathPattern").asText("").trim();
        boolean editNamed = toolName != null && toolName.endsWith(".update");
        if (editNamed) {
            // An edit names its row AND the version it read: without the
            // optimistic echo the save door would take the row's current
            // version and silently race whoever edited in between — a card
            // that cannot guarantee the anchor is banned here.
            long stubId = body.path("stubId").asLong(0L);
            int baseVersion = body.path("baseVersion").asInt(0);
            if (stubId <= 0 || baseVersion <= 0 || name.isEmpty()
                    || method.isEmpty() || pathPattern.isEmpty()) {
                log.warn("Mock-rule edit draft lacks stubId/baseVersion/name/method/pathPattern anchors, "
                        + "confirmation would fail; proposal omitted tool={} stubId={} baseVersion={}",
                        toolName, stubId, baseVersion);
                return null;
            }
            return new WriteProposal(toolName, "MOCK_STUB_WRITE",
                    "编辑 Mock 规则 #" + stubId + "「" + name + "」（" + method.toUpperCase()
                            + " " + pathPattern + "，确认后走接口仿真 → Mock 接口页同一保存链——名称与方法、"
                            + "路径模式、匹配器与响应结构照常在校验时复核，不通过会被拒绝并提示原因；"
                            + "规则基于第 " + baseVersion + " 版编辑，若期间已被他人修改会被拒绝并提示刷新）",
                    stubId, baseVersion, raw);
        }
        // Create needs the filing anchor (which space owns the row) plus the
        // three fields the save chain refuses without.
        long spaceId = body.path("spaceId").asLong(0L);
        if (spaceId <= 0 || name.isEmpty() || method.isEmpty() || pathPattern.isEmpty()) {
            log.warn("Mock-rule create draft lacks spaceId/name/method/pathPattern anchors; confirmation would fail; "
                    + "proposal omitted tool={} spaceId={} nameBlank={} methodBlank={} pathBlank={}",
                    toolName, spaceId, name.isEmpty(), method.isEmpty(), pathPattern.isEmpty());
            return null;
        }
        return new WriteProposal(toolName, "MOCK_STUB_WRITE",
                "在 Mock 空间 #" + spaceId + " 新建打桩规则「" + name + "」（" + method.toUpperCase()
                        + " " + pathPattern + "，确认后走接口仿真 → Mock 接口页同一保存链——名称与方法、"
                        + "路径模式、匹配器与响应结构、场景状态声明照常在校验时复核，不通过会被拒绝并提示原因；"
                        + "新规则须其所属空间处于发布态且规则启用才对外提供访问，空间发布始终是人工决定）",
                0L, 0, raw);
    }

    /**
 * AI case-generation batch submit .
 * The card names the requirement count so the human sees the cost scope
 * before confirming; every list-legality decision (blank line, per-line
 * length, the hot-updatable batch ceiling) is re-taken by the enqueue
 * chain at landing time. Submitting only queues generation — produced
 * cases still go through the human review loop, which the card states.
 */
    private static WriteProposal aiGenBatchShape(String toolName, JsonNode body, String raw) {
        JsonNode requirements = body.path("requirements");
        int usable = 0;
        if (requirements.isArray()) {
            for (JsonNode item : requirements) {
                if (!item.asText("").trim().isEmpty()) {
                    usable++;
                }
            }
        }
        if (usable == 0) {
            log.warn("AI-generation batch draft has no non-empty requirement items; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        return new WriteProposal(toolName, "AIGEN_BATCH_WRITE",
                "提交 " + usable + " 条需求进行 AI 批量生成用例（确认后走 AI 用例生成页同一批量提交链——需求条目非空、"
                        + "单条长度与单次条数上限照常在校验时复核，不通过会被拒绝并提示原因；提交仅是排队异步生成，"
                        + "产出的用例仍须人工在用例评审页逐条通过，不会自动生效）",
                0L, 0, raw);
    }

    /**
 * Defect create/edit/transition . Which
 * shape a call proposes is decided by the tool NAME (one kind, three
 * tools — the same posture as the tag family): create names its title
 * and lands the row in the initial state; edit and transition both name
 * the row AND the version the model read — without the optimistic echo
 * the save door would take the row's live version and silently race
 * whoever edited in between, so a card that cannot guarantee the anchor
 * is banned here. A transition toward the closed/reopened states must
 * carry the reason (the chain refuses without it), and the target state
 * must sit inside the machine's range — an out-of-range card could only
 * ever fail on confirmation.
 */
    private static WriteProposal defectShape(String toolName, JsonNode body, String raw) {
        String title = body.path("title").asText("").trim();
        if (toolName != null && toolName.endsWith(".create")) {
            if (title.isEmpty()) {
                log.warn("Defect-create draft lacks the title anchor; confirmation would fail; proposal omitted tool={}", toolName);
                return null;
            }
            return new WriteProposal(toolName, "DEFECT_WRITE",
                    "新建缺陷「" + title + "」（确认后走缺陷库页同一创建链——标题长度、严重级别、"
                            + "关联用例与计划同项目、修复人须为项目成员照常在创建时复核，不通过会被拒绝并提示原因；"
                            + "归属项目取当前会话项目，载荷无法指向其他项目；新单落为新建状态，"
                            + "后续处理中/已修复/验证中/已关闭的流转仍在缺陷库页由人工推进）",
                    0L, 0, raw);
        }
        long defectId = body.path("defectId").asLong(0L);
        int baseVersion = body.path("version").asInt(0);
        if (defectId <= 0 || baseVersion <= 0) {
            log.warn("Defect {} draft lacks defectId/version anchors; confirmation would fail; proposal omitted tool={} defectId={} version={}",
                    toolName != null && toolName.endsWith(".transition") ? "transition" : "edit",
                    toolName, defectId, baseVersion);
            return null;
        }
        if (toolName != null && toolName.endsWith(".transition")) {
            int toStatus = body.path("toStatus").asInt(0);
            String reason = body.path("closedReason").asText("").trim();
            if (toStatus < 2 || toStatus > 6) {
                log.warn("Defect-transition draft target status is outside the state machine; proposal omitted tool={} toStatus={}", toolName, toStatus);
                return null;
            }
            if ((toStatus == 5 || toStatus == 6) && reason.isEmpty()) {
                log.warn("Defect close/reopen draft lacks a reason; confirmation would fail; proposal omitted tool={} toStatus={}",
                        toolName, toStatus);
                return null;
            }
            return new WriteProposal(toolName, "DEFECT_WRITE",
                    "缺陷 #" + defectId + " 流转到「" + defectStatusLabel(toStatus) + "」（确认后走缺陷库页"
                            + "同一状态流转链——当前状态能否流转到目标状态、版本号是否为第 " + baseVersion
                            + " 版照常在校验时复核，若期间已被他人修改会被拒绝并提示刷新；关闭或重新打开必须填写原因）",
                    defectId, baseVersion, raw);
        }
        if (title.isEmpty()) {
            log.warn("Defect-edit draft lacks the title anchor; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        return new WriteProposal(toolName, "DEFECT_WRITE",
                "编辑缺陷 #" + defectId + "「" + title + "」（确认后走缺陷库页同一编辑链——标题长度、严重级别、"
                        + "修复人须为项目成员照常在校验时复核，不通过会被拒绝并提示原因；已关闭的缺陷不可编辑，"
                        + "需先重新打开；本次编辑基于第 " + baseVersion + " 版，若期间已被他人修改会被拒绝并提示刷新）",
                defectId, baseVersion, raw);
    }

    /** Status labels mirror the defect library's own wording (single-sourced on the card face). */
    private static String defectStatusLabel(int toStatus) {
        return switch (toStatus) {
            case 2 -> "处理中";
            case 3 -> "已修复";
            case 4 -> "验证中";
            case 5 -> "已关闭";
            case 6 -> "重新打开";
            default -> String.valueOf(toStatus);
        };
    }

    /**
 * Data-factory script create/edit ( stage 11, /data-factories
 * domain). Which side of the door the row lands on is decided by the
 * tool NAME (one kind, two tools — the same posture as the mock-stub
 * family). A create must carry its name, the script body, an env anchor
 * and a type inside the one-of set — every one of those is refused by
 * the save chain, so a card missing any of them could only ever bait a
 * failed confirmation. An edit names the row it edits; tp_data_factory
 * has no version ledger (updated in place like the api-test kind), so
 * the anchor is just the row id. Running the script — the part that
 * dials the real engine and writes the data pool — is never smuggled by
 * the card: it stays a deliberate human click, stated up front.
 */
    private static WriteProposal dataFactoryShape(String toolName, JsonNode body, String raw) {
        String name = body.path("name").asText("").trim();
        if (toolName != null && toolName.endsWith(".create")) {
            String content = body.path("content").asText("").trim();
            long envId = body.path("envId").asLong(0L);
            int type = body.path("type").asInt(0);
            if (name.isEmpty() || content.isEmpty() || envId <= 0 || type < 1 || type > 3) {
                log.warn("Data-factory create draft lacks name/content/envId anchors or the type is not in 1/2/3; confirmation would fail; proposal omitted tool={}",
                        toolName);
                return null;
            }
            return new WriteProposal(toolName, "DATA_FACTORY_WRITE",
                    "新建「" + dataFactoryTypeLabel(type) + "」造数脚本 " + name
                            + "（确认后走数据工厂页同一保存链——类型、环境归属本项目、数据键名格式与"
                            + "项目环境内占用冲突照常在校验时复核，不通过会被拒绝并提示原因；"
                            + "归属项目取当前会话项目，载荷无法指向其他项目；确认只保存脚本定义，"
                            + "运行造数仍在数据工厂页由人工点击执行）",
                    0L, 0, raw);
        }
        long factoryId = body.path("factoryId").asLong(0L);
        if (factoryId <= 0) {
            log.warn("Data-factory edit draft lacks the factoryId anchor; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        if (name.isEmpty() && body.path("content").asText("").trim().isEmpty()
                && !body.hasNonNull("status")) {
            log.warn("Data-factory edit draft has no updatable fields; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        return new WriteProposal(toolName, "DATA_FACTORY_WRITE",
                "编辑造数脚本 #" + factoryId + (name.isEmpty() ? "" : "「" + name + "」")
                        + "（确认后走数据工厂页同一保存链——改内容时数据键名格式与占用冲突照常复核；"
                        + "启用/停用随草案声明；脚本归属项目复核后方可写入，其他项目的脚本会被拒绝；"
                        + "运行造数仍在数据工厂页由人工点击执行）",
                factoryId, 0, raw);
    }

    /** Type labels mirror the data-factory page's own wording (single-sourced on the card face). */
    private static String dataFactoryTypeLabel(int type) {
        return switch (type) {
            case 1 -> "API";
            case 2 -> "SQL";
            case 3 -> "脚本";
            default -> String.valueOf(type);
        };
    }

    /**
 * Exploration session start ( stage 12, /explore domain — the last
 * debt leaf of the clearing batch). The card names the exploration goal
 * (the session being filed) and states the cost side up front: unlike
 * the definition-saving kinds, confirming THIS card hands the session to
 * the explore kernel, which spends real tokens. The budget fuse, the
 * goal/start-URL mandates, the web-form SSRF floor and the mobile-form
 * env whitelist are all re-decided by the same create chain the explore
 * page runs, so a card that could never land (blank goal, blank start)
 * is banned here instead of baiting a confirm click.
 */
    private static WriteProposal exploreCreateShape(String toolName, JsonNode body, String raw) {
        String goal = body.path("goalText").asText("").trim();
        String startUrl = body.path("startUrl").asText("").trim();
        if (goal.isEmpty() || startUrl.isEmpty()) {
            log.warn("Exploration-run draft lacks goalText/startUrl anchors; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        String goalShown = goal.length() > 60 ? goal.substring(0, 60) + "…" : goal;
        return new WriteProposal(toolName, "EXPLORE_CREATE",
                "发起自主探索：「" + goalShown + "」（起点 " + startUrl
                        + "；确认后走探索页同一创建链——达到上限自动停止、目标字数上限、起始地址合规底线"
                        + "（内网与元数据地址会被拒绝）与移动形态环境白名单照常在创建时复核，"
                        + "不通过会被拒绝并提示原因；归属项目取当前会话项目，载荷无法指向其他项目；"
                        + "确认即开始探索并占用项目 AI 预算，探索可在探索页随时取消）",
                0L, 0, raw);
    }

    /**
 * Flaky-quarantine proposal (AO batch , closed-loop
 * operations S2 emitter side). The card acts on an EXISTING case the
 * failure analysis observed, so BOTH anchors are mandatory: the case id
 * (the core door refuses a card with no target) and the version the
 * analysis read (the door re-checks the echo, and a 0/absent anchor
 * would conflict against every live row — a card that cannot guarantee
 * its anchor is banned here like in every other shape). The reason is
 * the evidence the human judges on the card face, so it is required
 * even though the shared setFlaky chain owns a default wording.
 * Confirming only MARKS the case flaky through that same chain: the
 * door is one-directional — releasing an isolated case stays the
 * cases page's human face.
 */
    private static WriteProposal flakyIsolationShape(String toolName, JsonNode body, String raw) {
        long caseId = body.path("caseId").asLong(0L);
        int baseVersion = body.path("baseVersion").asInt(0);
        String reason = body.path("reason").asText("").trim();
        if (caseId <= 0 || baseVersion <= 0 || reason.isEmpty()) {
            log.warn("Quarantine draft lacks caseId/baseVersion/reason anchors; confirmation would fail; proposal omitted tool={} caseId={} baseVersion={}",
                    toolName, caseId, baseVersion);
            return null;
        }
        return new WriteProposal(toolName, "FLAKY_ISOLATION",
                "标记用例 #" + caseId + " 为流畅隔离（" + reason
                        + "；确认后走用例工作台一键隔离同一链——审计行与隔离原因幂等刷新由该链携带，用例不属于会话项目或期间已被修改（草案基于第 "
                        + baseVersion + " 版）会被拒绝并提示原因；本通道只支持隔离不支持解除，解除仍在用例库页由人工操作）",
                caseId, baseVersion, raw);
    }

    /**
 * Requirement point create/edit . Which side
 * of the door the row lands on is decided by the tool NAME (one kind, two
 * tools — the same posture as the case and defect families). A create
 * must carry its title (the shared RequirementService chain refuses a
 * blank one); an edit must name the row AND the version the model read —
 * without the optimistic echo the save door would take the row's live
 * version and silently race whoever edited in between, so a card that
 * cannot guarantee the anchor is banned here like in every other shape.
 * The owning project is NOT part of the payload: it comes from the
 * draft's own session at landing time, so a forged call can never write
 * into another project's requirement list. Version-chain snapshots,
 * soft-delete visibility and the version-conflict refusal (which names
 * the live version) all stay inside that same shared chain — the human
 * confirmation click on the card is the only thing this door unlocks.
 */
    private static WriteProposal requirementShape(String toolName, JsonNode body, String raw) {
        String title = body.path("title").asText("").trim();
        if (toolName != null && toolName.endsWith(".update")) {
            long requirementId = body.path("requirementId").asLong(0L);
            int baseVersion = body.path("baseVersion").asInt(0);
            if (requirementId <= 0 || baseVersion <= 0) {
                log.warn("Requirement-edit draft lacks requirementId/baseVersion anchors; confirmation would fail; proposal omitted tool={} requirementId={} baseVersion={}",
                        toolName, requirementId, baseVersion);
                return null;
            }
            return new WriteProposal(toolName, "REQUIREMENT_WRITE",
                    "编辑需求点 #" + requirementId + (title.isEmpty() ? "" : "「" + title + "」")
                            + "（确认后走需求管理页同一保存链——标题、验收标准与章节照常在校验时复核，不通过会被拒绝并提示原因；"
                            + "本次编辑基于第 " + baseVersion + " 版，若期间已被他人修改会被拒绝并点名当前版本）",
                    requirementId, baseVersion, raw);
        }
        if (title.isEmpty()) {
            log.warn("Requirement-create draft lacks the title anchor; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        return new WriteProposal(toolName, "REQUIREMENT_WRITE",
                "新建需求点「" + title + "」（确认后走需求管理页同一创建链——标题与章节照常在校验时复核，不通过会被拒绝并提示原因；"
                        + "归属项目取当前会话项目，载荷无法指向其他项目；需求来源记为手工录入）",
                0L, 0, raw);
    }

    /**
 * Checked-subset login-state registration (screenshot capture batch
 * piece D, emitter side). The card proposes filing the rows the human
 * ticks on the card face: {@code stagingToken} routes back to the
 * TTL-bound transient slot holding the PARSED VALUES (values never pass
 * through the model — piece E red line), {@code stateName} names the new
 * login-state row, and the checked list is the explicit authorisation the
 * landing strategy re-verifies row by row. Missing anchors could only
 * ever bait a failed confirmation, so they get no card — the
 * honest-absence rule of every other shape here.
 * <p>Structural value gate: the payload snapshot is RE-SERIALISED from
 * the anchors only (token, state name, per-row name/selected). Whatever
 * else the model echoed — including a copied-back credential value — is
 * dropped here, so no draft row's payload_json and no card face can ever
 * carry a value; the landing chain reloads values from the slot at
 * confirm time and the vault owns the only persistent copy (encrypted).
 */
    private static WriteProposal loginStateShape(String toolName, JsonNode body,
                                                 ObjectMapper reader,
                                                 com.sixbean.testpike.aiservice.aichat.domain.component
                                                         .LoginStateStagedNames stagedNames,
                                                 java.util.List<String> liveStagingTokens) {
        String token = body.path("stagingToken").asText("").trim();
        String stateName = body.path("stateName").asText("").trim();
        JsonNode entries = body.path("entries");
        if (token.isEmpty() || stateName.isEmpty() || !entries.isArray()) {
            log.warn("Login-state registration draft lacks stagingToken/stateName anchors or the item list is unavailable; confirmation would fail; proposal omitted "
                    + "tool={} tokenBlank={} nameBlank={}", toolName, token.isEmpty(), stateName.isEmpty());
            return null;
        }
        // Piece F : the transient slot is the single source of the card
        // name domain. The model transcribes names off a screenshot, so case
        // and whitespace drift plus outright hallucinations are expected
        // traffic; every such row used to ride the card and then trip the
        // landing door's fail-closed per-row check at confirm time, forever
        // (the "fake usable" second shape). Rows are rewritten onto the
        // staged spelling here — what the human ticks then equals, byte for
        // byte, what the door re-reads. A slot the server cannot answer
        // (expired, gone, probe refused) mints no card: honest absence.
        java.util.List<String> staged = stagedNames == null
                ? java.util.List.of() : stagedNames.names(token);
        java.util.Map<String, String> canonicalByNormalized = nameDomain(staged);
        // B1 fix (BD evidence): a transcribed pointer that routes to
        // a DEAD slot used to abstain even when this round had just staged a
        // live one — the previous turn's observation block keeps showing the
        // token leg A deleted on landing (R57), and the model transcribes
        // that plausible spelling. This is the R51 shape: injected evidence ×
        // transcription meeting without a single-point reconciliation. The
        // server holds the live tokens, so the reconcile happens HERE, at
        // the one consumption point: exactly one readable live slot re-hangs
        // the routing anchor; zero or several stay fail-closed, because
        // guessing which slot was meant would land rows the human never saw.
        if (canonicalByNormalized.isEmpty() && stagedNames != null) {
            java.util.List<String> readable = new java.util.ArrayList<>();
            for (String candidate : liveStagingTokens == null
                    ? java.util.List.<String>of() : liveStagingTokens) {
                String trimmed = candidate == null ? "" : candidate.trim();
                if (trimmed.isEmpty() || trimmed.equals(token) || readable.contains(trimmed)) {
                    continue;
                }
                if (!nameDomain(stagedNames.names(trimmed)).isEmpty()) {
                    readable.add(trimmed);
                }
            }
            if (readable.size() == 1) {
                token = readable.get(0);
                staged = stagedNames.names(token);
                canonicalByNormalized = nameDomain(staged);
                log.warn("Login-state draft transcribed staging token points to an expired slot while this round holds one live staging slot;"
                        + " re-attach the live token before emitting the card tool={} liveToken={}", toolName, token);
            }
        }
        if (canonicalByNormalized.isEmpty()) {
            log.warn("Login-state draft staging-slot name domain unreadable or expired; confirmation would fail; proposal omitted tool={}", toolName);
            return null;
        }
        // Piece D3 kept: EVERY aligned candidate rides the snapshot (unticked
        // rows with selected:false) so the card renders the full check table
        // and the human can re-tick before asking the confirm ticket. Ticking
        // is the authorisation act — the landing door still fails closed on
        // zero ticks, the card does not vanish.
        java.util.Set<String> alignedRows = new java.util.LinkedHashSet<>();
        java.util.Set<String> tickedRows = new java.util.LinkedHashSet<>();
        for (JsonNode entry : entries) {
            String name = entry.path("name").asText("").trim();
            if (name.isEmpty()) {
                continue;
            }
            String folded = cardNameAlignKey(name);
            String canonical = canonicalByNormalized.get(folded);
            if (canonical == null) {
                // Piece F third transcription shape (leg1 probe 17:08:03
                // WARN): the staging summary line carries a red-mark
                // annotation glued behind the name, and the model
                // transcribes the whole line. The annotation is display
                // noise — strip trailing bracketed clauses and retry the
                // SAME fail-closed lookups; a stripped name that still
                // misses the staged domain is dropped exactly as before.
                String annotationFree = cardNameAlignKey(stripTrailingAnnotations(name));
                if (!annotationFree.isEmpty() && !annotationFree.equals(folded)) {
                    canonical = canonicalByNormalized.get(annotationFree);
                    if (canonical == null) {
                        folded = annotationFree;
                    }
                }
            }
            if (canonical == null) {
                // Piece F three-fix ( segment3 rerun evidence, 16:00:29
                // WARN family): the Application panel ALWAYS shows names under
                // a section header, and the model transcribes that header
                // fused into the name ("local_storage auth token" for the
                // bare staged "auth_token"). Folded equality then misses
                // every row and the card honestly abstained — four attempts,
                // same shape. Fallback = UNIQUE folded suffix hit onto the
                // staged domain; zero or multiple hits stay fail-closed,
                // because guessing a name would authorise a row under a
                // spelling the human never saw on the card face.
                String suffixCandidate = null;
                int suffixHits = 0;
                for (java.util.Map.Entry<String, String> stagedEntry
                        : canonicalByNormalized.entrySet()) {
                    String stagedKey = stagedEntry.getKey();
                    if (!stagedKey.isEmpty() && folded.endsWith(stagedKey)) {
                        suffixHits++;
                        suffixCandidate = stagedEntry.getValue();
                    }
                }
                if (suffixHits == 1) {
                    canonical = suffixCandidate;
                } else if (suffixHits > 1) {
                    log.warn("Login-state draft transcribed-name folded suffix hits multiple staging names; drop instead of forcing a pick "
                            + "tool={} transcribed={} hits={}", toolName, name, suffixHits);
                    continue;
                }
            }
            if (canonical == null) {
                log.warn("Login-state draft drops transcribed names outside the staging-name domain tool={} transcribed={}", toolName, name);
                continue;
            }
            alignedRows.add(canonical);
            if (entry.path("selected").asBoolean(false)) {
                tickedRows.add(canonical);
            }
        }
        if (alignedRows.isEmpty()) {
            log.warn("Login-state draft transcribed names all fall outside the staging-name domain; the card has nothing selectable; proposal omitted tool={}", toolName);
            return null;
        }
        com.fasterxml.jackson.databind.node.ObjectNode payload = reader.createObjectNode();
        payload.put("stagingToken", token);
        payload.put("stateName", stateName);
        com.fasterxml.jackson.databind.node.ArrayNode rows = payload.putArray("entries");
        for (String rowName : alignedRows) {
            com.fasterxml.jackson.databind.node.ObjectNode row = rows.addObject();
            row.put("name", rowName);
            row.put("selected", tickedRows.contains(rowName));
        }
        int checked = tickedRows.size();
        String payloadJson;
        try {
            payloadJson = reader.writeValueAsString(payload);
        } catch (JsonProcessingException impossibleForATreeBuiltHere) {
            log.warn("Login-state draft payload re-serialization failed; proposal omitted tool={}", toolName);
            return null;
        }
        return new WriteProposal(toolName, "LOGIN_STATE_WRITE",
                "登记登录态「" + stateName + "」（勾选 " + checked
                        + " 条，取值来自服务端截图解析结果，凭证值不经过对话内容；确认后走登录态管理页同一入册链——"
                        + "加密入保险库、同名续期与执行机重放核验照常复核，不通过会被拒绝并提示原因）",
                0L, 0, payloadJson);
    }

    /**
     * Folded name domain of one staged slot (B1 extraction of the pre-existing
     * loop, single-sourced because the reconciliation probe needs the same
     * readability test for every candidate live token).
     */
    private static java.util.Map<String, String> nameDomain(java.util.List<String> staged) {
        java.util.Map<String, String> byNormalized = new java.util.LinkedHashMap<>();
        for (String stagedName : staged == null ? java.util.List.<String>of() : staged) {
            if (stagedName != null && !stagedName.isBlank()) {
                byNormalized.putIfAbsent(cardNameAlignKey(stagedName), stagedName.trim());
            }
        }
        return byNormalized;
    }

    /**
     * Alignment key of one card name (piece F, connector-fold refinement):
     * trim, lowercase, then fold every run of connector characters (space,
     * underscore, hyphen). OCR drops connectors off DevTools panel names —
     * "auth_token" parses as "auth token" — while the model re-transcribes
     * the plausible underscored spelling back, so the staged side and the
     * transcribed side only match once connectors are folded on both. The
     * canonical VALUE written to the payload stays the staged bytes: the
     * landing door re-checks each authorised name against the slot byte for
     * byte, and that gate is deliberately not relaxed here.
     */
    private static String cardNameAlignKey(String raw) {
        return raw.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    /**
     * A trailing bracketed annotation — ASCII or full-width parens, as the
     * staging summary line glues its red-mark flag behind the row name and
     * the model transcribes the whole line verbatim. Only END-OF-STRING
     * clauses are peeled (repeatedly, for stacked annotations); a name that
     * is nothing but an annotation is returned unchanged so it still fails
     * closed instead of being guessed into some other row.
     */
    private static final java.util.regex.Pattern TRAILING_ANNOTATION =
            java.util.regex.Pattern.compile("[（(][^（）()]*[)）]\\s*$");

    private static String stripTrailingAnnotations(String name) {
        String current = (name == null ? "" : name).trim();
        while (!current.isEmpty()) {
            String stripped = TRAILING_ANNOTATION.matcher(current).replaceAll("").trim();
            if (stripped.equals(current) || stripped.isEmpty()) {
                break;
            }
            current = stripped;
        }
        return current;
    }

    /** Tag create/rename/merge .
 * Shape comes from the payload anchors — a merge always names both tags,
 * a rename names its tag and the new name, a create names only a label. */
    /**
     * Interface pass-through change proposal: the model named one catalog entry plus its declared
     * arguments, and the human is about to approve a call against this application's own door.
     *
     * <p>Honest absence when the entry cannot be scoped: a proposal without a project anchor would
     * be refused by the landing door anyway (the owning project is taken from the chat session and
     * must match), and showing a card that is guaranteed to fail is worse than saying nothing.
     */
    private static WriteProposal apiAccessShape(String toolName, JsonNode body, String raw,
                                                 long sessionProjectId) {
        String endpointKey = body.path("endpointKey").asText("");
        if (endpointKey.isBlank()) {
            log.warn("interface pass-through proposal has no endpointKey, honest absence tool={}", toolName);
            return null;
        }
        // The conversation's server-side segment owns the anchor; a payload id is
        // only consulted when the caller carries no segment (legacy generation
        // link). The landing door re-stamps and re-checks either way — the card
        // merely states the truth it already knows.
        long projectId = sessionProjectId > 0
                ? sessionProjectId
                : body.path("arguments").path("projectId").asLong(0L);
        if (projectId <= 0) {
            log.warn("interface pass-through proposal lacks a project anchor, honest absence key={}",
                    endpointKey);
            return null;
        }
        return new WriteProposal(toolName, "API_ACCESS_WRITE",
                "接口直通变更：" + endpointKey.trim() + "（项目 #" + projectId + "，确认后按声明字段调用）",
                0L, 0, raw);
    }

    private static WriteProposal tagShape(String toolName, JsonNode body, String raw) {
        long fromTagId = body.path("fromTagId").asLong(0L);
        if (fromTagId > 0) {
            long toTagId = body.path("toTagId").asLong(0L);
            if (toTagId <= 0) {
                log.warn("Tag-merge draft lacks the toTagId anchor, cannot land safely; proposal omitted fromTagId={}", fromTagId);
                return null;
            }
            return new WriteProposal(toolName, "TAG_WRITE",
                    "合并标签 #" + fromTagId + " → #" + toTagId + "（关联用例整体迁移）",
                    fromTagId, 0, raw);
        }
        long tagId = body.path("tagId").asLong(0L);
        if (tagId > 0) {
            String newName = body.path("newName").asText("");
            if (newName.isBlank()) {
                log.warn("Tag-rename draft lacks newName, cannot land; proposal omitted");
                return null;
            }
            return new WriteProposal(toolName, "TAG_WRITE",
                    "标签 #" + tagId + " 改名为「" + newName.trim() + "」",
                    tagId, 0, raw);
        }
        String name = body.path("name").asText("");
        if (name.isBlank()) {
            log.warn("Tag-create draft lacks a name, cannot land; proposal omitted");
            return null;
        }
        return new WriteProposal(toolName, "TAG_WRITE", "新建标签：" + name.trim(),
                0L, 0, raw);
    }

    /** Human-readable field list of an edit proposal (anchors excluded, JSON order). */
    private static String changedFieldList(JsonNode body, String... anchors) {
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (String anchor : anchors) {
            skip.add(anchor);
        }
        StringBuilder fields = new StringBuilder();
        body.fieldNames().forEachRemaining(field -> {
            if (skip.contains(field)) {
                return;
            }
            if (fields.length() > 0) {
                fields.append('、');
            }
            fields.append(field);
        });
        return fields.toString();
    }
}
