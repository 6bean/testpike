package com.sixbean.testpike.aiservice.gen.infrastructure.remote;

import com.sixbean.testpike.aiservice.aichat.infrastructure.remote.ScriptView;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmConfigBrief;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpInvocationRemoteInput;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockLlmFallbackStoreBrief;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockLlmFallbackStoreInput;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreGrantBrief;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExecTaskResultRemote;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreStepRecord;
import com.sixbean.testpike.aiservice.repair.infrastructure.remote.RepairTaskView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.ExploreRunStatsUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class CoreGateway {

    private final RestClient rest;

    public CoreGateway(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    
    public void markRunning(long taskId) {
        call(HttpMethod.POST, "/internal/ai-tasks/" + taskId + "/running", null, null, 0L);
    }

    
    public void markDone(long taskId, long caseId) {
        Map<String, Long> body = new LinkedHashMap<>();
        body.put("caseId", caseId);
        call(HttpMethod.POST, "/internal/ai-tasks/" + taskId + "/done", body, null, 0L);
    }

 
    public void markFailed(long taskId, String errorCode, String errorMsg) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("errorCode", errorCode);
        body.put("errorMsg", errorMsg);
        call(HttpMethod.POST, "/internal/ai-tasks/" + taskId + "/failed", body, null, 0L);
    }

    
    public ProjectBrief getProject(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ProjectBrief>>() {
                }, 0L);
    }

    
    public List<EnvBrief> listProjectEnvs(long projectId) {
        List<EnvBrief> data = call(HttpMethod.GET, "/internal/projects/" + projectId + "/envs",
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<EnvBrief>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    /**
     * Write-risk page door (GET /internal/page-write-profiles/risk-pages):
     * the explore kernel annotates its prompt and gates click/input
     * dispatch on these rows. A core refusal surfaces as ServiceException
     * for the caller's fail-soft posture (never read as "the project has
     * no risk pages").
     */
    public List<com.sixbean.testpike.common.webelement.PageWriteRiskView> listPageWriteRiskPages(
            long projectId) {
        List<com.sixbean.testpike.common.webelement.PageWriteRiskView> data = call(
                HttpMethod.GET, "/internal/page-write-profiles/risk-pages?projectId=" + projectId,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        List<com.sixbean.testpike.common.webelement.PageWriteRiskView>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    /**
 * Read-only enabled database-connection NAME door for the stage-2
 * mechanical gap fill ( knife 2; core rows via the existing
 * {@code GET /internal/projects/{id}/env-connections}): keeps status=1
 * rows, resolves the draft's env NAME through the same envs door the
 * probe already rides (case-insensitive), and mirrors ONLY
 * envId/name/status — jdbcUrl/username/password never cross this edge.
 * A core refusal throws instead of folding into an empty list, so the
 * caller keeps its original verdict rather than reading a door outage as
 * "the project has no database" (P18 three-state posture). Blank envName
 * = project-wide enabled names (the unambiguous-fill posture).
 */
    public List<String> listEnabledConnectionNames(long projectId, String envName) {
        List<DbConnectionBrief> rows = call(HttpMethod.GET,
                "/internal/projects/" + projectId + "/env-connections", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<DbConnectionBrief>>>() {
                }, 0L);
        if (rows == null) {
            rows = List.of();
        }
        long envId = -1L;
        if (envName != null && !envName.isBlank()) {
            EnvBrief env = listProjectEnvs(projectId).stream()
                    .filter(e -> e.name() != null && e.name().equalsIgnoreCase(envName.trim()))
                    .findFirst().orElse(null);
            if (env == null || env.id() == null) {
                return List.of();
            }
            envId = env.id();
        }
        final long wanted = envId;
        return rows.stream()
                .filter(r -> r != null && r.status() == 1
                        && r.name() != null && !r.name().isBlank())
                .filter(r -> wanted < 0 || r.envId() == wanted)
                .map(DbConnectionBrief::name)
                .toList();
    }

    /**
 * Help-chapter index over the existing core internal endpoint (AO batch
 * grounding source; zero core change — HelpDocBrief mirrors the
 * {@code HelpDocInfo} line names, index rows carry no body by contract).
 * Callers fail-soft: a core outage degrades the dialog to honest
 * zero-grounding, never to a failed turn.
 */
    public List<HelpDocBrief> listHelpDocs() {
        List<HelpDocBrief> data = call(HttpMethod.GET, "/internal/help/docs", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<HelpDocBrief>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    /** Chapters of one help domain WITH bodies (detail state of the same endpoint). */
    public List<HelpDocBrief> getHelpDomainChapters(String domainCode) {
        List<HelpDocBrief> data = call(HttpMethod.GET, "/internal/help/docs/" + domainCode, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<HelpDocBrief>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    
    public RequirementBrief getRequirement(long requirementId) {
        return call(HttpMethod.GET, "/internal/requirements/" + requirementId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<RequirementBrief>>() {
                }, 0L);
    }

    
    public Map<String, Object> getCase(long caseId) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, 0L);
    }

    
    public List<Map<String, Object>> listAppautoEngines() {
        try {
            List<Map<String, Object>> data = call(HttpMethod.GET, "/internal/appauto/engines", null,
                    new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                    }, 0L);
            return data == null ? List.of() : data;
        } catch (Exception e) {
            return List.of();
        }
    }

    
    public Map<String, Object> createCase(long operatorId, Map<String, Object> input) {
        return call(HttpMethod.POST, "/internal/cases", input,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, operatorId);
    }

    
    public void linkRequirement(long requirementId, List<Long> caseIds, long operatorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseIds", caseIds);
        call(HttpMethod.POST, "/internal/requirements/" + requirementId + "/cases", body, null, operatorId);
    }

    
    public void saveScript(long caseId, String schemaVersion, String actionJson,
                           int generatedBy, long operatorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", caseId);
        body.put("schemaVersion", schemaVersion);
        body.put("actionJson", actionJson);
        body.put("generatedBy", generatedBy);
        body.put("operatorId", operatorId > 0 ? operatorId : null);
        call(HttpMethod.POST, "/internal/scripts", body, null, operatorId);
    }

 /**
  * Honest-mark confidence clamp write-back: raises a stored confidence to the
  * cap ONLY when the row claims a tighter level (1 = top), riding the
  * field-level case door PUT /internal/cases/{id} (all other fields null =
  * untouched) with the optimistic version read from the same row. A replay
  * after the clamp reads the capped value and writes nothing (idempotent).
  */
 public void clampCaseConfidence(long caseId, int levelCap, long operatorId) {
     Map<String, Object> caseInfo = getCase(caseId);
     if (caseInfo == null) {
         return;
     }
     Object stored = caseInfo.get("confidence");
     Object version = caseInfo.get("version");
     if (!(stored instanceof Number current) || !(version instanceof Number lock)
             || current.intValue() >= levelCap) {
         return;
     }
     Map<String, Object> input = new LinkedHashMap<>();
     input.put("confidence", levelCap);
     Map<String, Object> body = new LinkedHashMap<>();
     body.put("input", input);
     body.put("version", lock.intValue());
     call(HttpMethod.PUT, "/internal/cases/" + caseId, body, null, operatorId);
 }

 
 public void createNotification(long userId, Long projectId, String eventType,
                                   String title, String content, String dedupKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("projectId", projectId);
        body.put("eventType", eventType);
        body.put("title", title);
        body.put("content", content);
        body.put("dedupKey", dedupKey);
        call(HttpMethod.POST, "/internal/notifications", body, null, 0L);
    }

    
    public List<Long> listAdminRecipients() {
        List<Long> data = call(HttpMethod.GET, "/internal/notifications/admin-recipients", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Long>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    
    public LlmConfigBrief getProjectLlmConfig(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/llm-config/brief", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<LlmConfigBrief>>() {
                }, 0L);
    }

 
    public void markModel(long taskId, String modelName) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("modelName", modelName);
        call(HttpMethod.POST, "/internal/ai-tasks/" + taskId + "/model", body, null, 0L);
    }

    /**
 *
 * <p>Scope = generation domain only ({@code modelType=1}): an unscoped read also
 * returns embedding rows, and handing one to the chat chain makes the provider
 * reject the call, cool down the gateway deployment and break the embedding calls
 * behind it.
 */
    public List<LlmConfigBrief> listEnabledLlmConfigs() {
        try {
            // Generation domain only: an unscoped read returns every enabled row,
            // embedding rows included, and the chat chain would then send an
            // embedding model name to /chat/completions (provider 404, gateway
            // cooldown, and the embedding calls behind it fail with 429).
            List<LlmConfigBrief> data = call(HttpMethod.GET,
                    "/internal/llm-configs/briefs?modelType=1", null,
                    new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<LlmConfigBrief>>>() {
                    }, 0L);
            return data == null ? List.of() : data;
        } catch (Exception e) {
            return List.of();
        }
    }

    
    public LlmConfigBrief getProjectEmbeddingConfig(long projectId) {
        return call(HttpMethod.GET, "/internal/projects/" + projectId + "/embedding-config/brief", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<LlmConfigBrief>>() {
                }, 0L);
    }

    
    public List<LlmConfigBrief> listEnabledEmbeddingConfigs() {
        try {
            List<LlmConfigBrief> data = call(HttpMethod.GET, "/internal/llm-configs/briefs?modelType=2", null,
                    new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<LlmConfigBrief>>>() {
                    }, 0L);
            return data == null ? List.of() : data;
        } catch (Exception e) {
            return List.of();
        }
    }

    
    public List<Map<String, Object>> listUsageCalibration(int days) {
        try {
            List<Map<String, Object>> data = call(HttpMethod.GET,
                    "/internal/llm-usage/calibration?days=" + days, null,
                    new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                    }, 0L);
            return data == null ? List.of() : data;
        } catch (Exception e) {
            return List.of();
        }
    }

    
    public void writeAudit(LlmAuditRequest audit) {
        try {
            call(HttpMethod.POST, "/internal/audit-logs", audit, null,
                    audit.operatorId() == null ? 0L : audit.operatorId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(CoreGateway.class)
                    .warn("LLM 降级审计写入失败（不阻断生成链路）action={} err={}",
                            audit.action(), e.getMessage());
        }
    }

    
    public void writeDuplicateDrop(String objectId, long projectId, Long operatorId,
                                   java.util.Map<String, Object> before) {
        writeAudit(new LlmAuditRequest(operatorId, projectId, "mq_consume", objectId,
                "MQ_DUPLICATE_DROP", before, java.util.Map.of("action", "skip_idempotent")));
    }

    
    public void writeKbGateSkip(String objectId, long projectId, Long operatorId,
                                java.util.Map<String, Object> before) {
        writeAudit(new LlmAuditRequest(operatorId, projectId, "mq_consume", objectId,
                "KB_GATE_SKIP", before, java.util.Map.of("action", "skip_kb_disabled")));
    }

    /**
 * acked a message whose target row is already deleted — the drop must stay
 * traceable, never silent. Same posture as writeDuplicateDrop (write failure
 * only warns, never blocks the ack).
 */
    public void writeEntityGoneSkip(String objectId, long projectId, Long operatorId,
                                    java.util.Map<String, Object> before) {
        writeAudit(new LlmAuditRequest(operatorId, projectId, "mq_consume", objectId,
                "MQ_ENTITY_GONE_SKIP", before, java.util.Map.of("action", "skip_entity_gone")));
    }

    
    public String getSysConfig(String key) {
        try {
            String data = call(HttpMethod.GET, "/internal/configs/value?key=" + key + "&defaultValue=", null,
                    new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                    }, 0L);
            return (data == null || data.isBlank()) ? null : data;
        } catch (Exception e) {
            return null;
        }
    }

    
    public void recordLlmUsage(com.sixbean.testpike.aiservice.llm.domain.component.CalibrationSample sample) {
        call(HttpMethod.POST, "/internal/llm-usage/samples", sample, null, 0L);
    }

    /**
 * Page element library prompt-summary rows (WE S5, piece B): the
 * core door answers confirmed elements, confidence desc, capped rows
 * {name, pageKey, anchors:[{type,value,...}]}. Read failures surface as
 * ServiceException via RestClientInvoker — the caller (bridge) degrades
 * to the empty segment (fail-soft, generation never blocks).
 */
    public List<Map<String, Object>> getLedgerPromptSummary(long projectId, String pageKey,
                                                            int limit) {
        String query = "/internal/web-elements/prompt-summary?projectId=" + projectId
                + (pageKey == null || pageKey.isBlank() ? ""
                        : "&pageKey=" + java.net.URLEncoder.encode(pageKey,
                                java.nio.charset.StandardCharsets.UTF_8))
                + "&limit=" + limit;
        return call(HttpMethod.GET, query, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                }, 0L);
    }

    
    public List<Map<String, Object>> getDocumentChunks(long documentId) {
        return call(HttpMethod.GET, "/internal/documents/" + documentId + "/chunks", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                }, 0L);
    }

    
    public List<Map<String, Object>> getCaseTags(long caseId) {
        return call(HttpMethod.GET, "/internal/tags/cases/" + caseId + "/tags", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                }, 0L);
    }

    
    public Map<String, Object> getDocument(long documentId) {
        return call(HttpMethod.GET, "/internal/documents/" + documentId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, 0L);
    }

    /**
 * Batch detail for dialog @-mention chips (AO batch knife 4c; core
 * GET /internal/batches/{id} BatchInfo mirror). A missing row surfaces as
 * a ServiceException with the not-found code — the caller names the chip
 * in its honest refusal line.
 */
    public Map<String, Object> getBatch(long batchId) {
        return call(HttpMethod.GET, "/internal/batches/" + batchId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, 0L);
    }

    /**
 * Batch failure-insight landing write-back (AO-7 stage 2b; core
 * POST /internal/batches/{id}/insight). Pure pass-through — batch existence
 * validation and the server-side project-id derivation are single-point in
 * the core service (two-sides separation: ai-service never writes business
 * tables directly). A failed call propagates as ServiceException → container
 * retry → dead letter (the consumer must not swallow it).
 */
    public void upsertBatchInsight(long batchId, java.util.List<java.util.Map<String, Object>> clusters,
                                   int unattributedCount, int failCount, String summary) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("clusters", clusters);
        body.put("unattributedCount", unattributedCount);
        body.put("failCount", failCount);
        body.put("summary", summary);
        call(HttpMethod.POST, "/internal/batches/" + batchId + "/insight", body, null, 0L);
    }

    /**
 * Defect-link detail for dialog @-mention chips (AO batch knife 4c;
 * core GET /internal/defects/{id} DefectInfo mirror). The row carries the
 * owning caseId, not a projectId — the resolver slice-checks through it.
 */
    public Map<String, Object> getDefect(long defectId) {
        return call(HttpMethod.GET, "/internal/defects/" + defectId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, 0L);
    }

    
    public List<SkillAssemblyRemoteItem> getProjectAssembly(long projectId) {
        List<SkillAssemblyRemoteItem> data = call(HttpMethod.GET,
                "/internal/skills/project/" + projectId + "/assembly", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<SkillAssemblyRemoteItem>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    
    public void recordSkillUsages(List<SkillUsageRemoteInput> traces) {
        call(HttpMethod.POST, "/internal/skills/usage", traces, null, 0L);
    }

    
    public void recordKnowledgeHits(List<KnowledgeHitRemoteInput> hits) {
        call(HttpMethod.POST, "/internal/knowledge/hits", hits, null, 0L);
    }

    /**
 * <p>AO batch stage 1: the actor header drives core's five-way gate
 * verdict — a non-member caller gets 40301 from the assembly endpoint
 * itself, so the model never even sees foreign slices.
 *
 * @param actorId dialog/generation initiator; null = legacy header-less call
 */
    public List<ToolFcAssemblyRemoteItem> getProjectFcAssembly(long projectId, Long actorId) {
        List<ToolFcAssemblyRemoteItem> data = call(HttpMethod.GET,
                "/internal/tools/project/" + projectId + "/assembly", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<ToolFcAssemblyRemoteItem>>>() {
                }, actorId == null ? 0L : actorId);
        return data == null ? List.of() : data;
    }

    /** Legacy header-less call (null actor = core old verdict; kept for the pre- seam). */
    public List<ToolFcAssemblyRemoteItem> getProjectFcAssembly(long projectId) {
        return getProjectFcAssembly(projectId, null);
    }

    /**
 * Draft-card routing facts of the project segment : which
 * published+enabled write tools carry a draft objectType. The emitter
 * consults this instead of memorising tool names, so registering a new
 * write tool with a kind token is enough to make its calls produce cards.
 * Reads through the same actor header as the FC assembly so the R34
 * visibility union (project ∪ system-level) is core-single-sourced.
 */
    public List<com.sixbean.testpike.aiservice.aichat.domain.component.DraftToolKind>
            getDraftToolKinds(long projectId, long actorId) {
        List<com.sixbean.testpike.aiservice.aichat.domain.component.DraftToolKind> data =
                call(HttpMethod.GET,
                "/internal/tools/project/" + projectId + "/draft-kinds", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        List<com.sixbean.testpike.aiservice.aichat.domain.component
                                .DraftToolKind>>>() {
                }, actorId);
        return data == null ? List.of() : data;
    }

    // ---- dialog frame ledger door ----

    /**
     * Open one dialog turn on the ledger (status=streaming); returns the message
     * id that every frame of this turn is written against. The caller is the
     * ai-service answer pipeline — user echoes and assistant turns share this
     * door so the session never holds an assistant row without its user row.
     * Exceptions surface to the caller (fail-closed: a turn with no ledger row
     * must not stream untracked output).
     */
    public long openChatMessage(long sessionId, String role, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", role);
        body.put("content", content);
        Long id = call(HttpMethod.POST, "/internal/ai-chat/sessions/" + sessionId + "/messages",
                body, new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return id == null ? 0L : id;
    }

    /**
     * Persist one frame of the streaming turn; returns its allocated session
     * seq (the SSE {@code id:} a client acks with Last-Event-ID). {@code
     * frameType} crosses as the wire-name string — the ledger itself refuses an
     * unknown name with a reason naming the legal values (and heartbeats, which
     * never reach this door: they carry no seq by contract).
     */
    public long appendChatFrame(long sessionId, long messageId, String frameType,
                                 String payload, String metaJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", messageId);
        body.put("frameType", frameType);
        body.put("payload", payload);
        body.put("metaJson", metaJson);
        Long seq = call(HttpMethod.POST, "/internal/ai-chat/sessions/" + sessionId + "/frames",
                body, new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return seq == null ? 0L : seq;
    }

    /** Current session cursor — the anchor a reconnect compares against. */
    public long lastChatSeq(long sessionId) {
        Long seq = call(HttpMethod.GET, "/internal/ai-chat/sessions/" + sessionId + "/last-seq",
                null, new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return seq == null ? 0L : seq;
    }

    /**
 * Accumulated session token ledger : read once
 * per turn at the orchestration boundary, never inside the frame loop.
 * A door failure surfaces to the caller, which treats it as "unknown
 * consumption" and lets the turn proceed (fail-open on the read path:
 * the ledger catches up on the next turn; refusing every turn because a
 * billing read hiccuped would take the dialog down for a cost number).
 */
    public long sessionTotalTokens(long sessionId) {
        Long tokens = call(HttpMethod.GET, "/internal/ai-chat/sessions/" + sessionId + "/total-tokens",
                null, new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return tokens == null ? 0L : tokens;
    }

    /**
 * The sealed dialog history of one session a read door,
 * core GET /internal/ai-chat/sessions/{id}/messages?userId=): closed turns
 * oldest first, ownership enforced core-side against the calling user id.
 * The rolling assembly window consumes exactly this door — no second
 * history chain. Door failures surface to the orchestrator, which falls
 * back to the honest single-turn shape instead of killing the answer.
 */
    public java.util.List<ChatMessageRemote> listChatMessages(long userId, long sessionId) {
        java.util.List<ChatMessageRemote> turns = call(HttpMethod.GET,
                "/internal/ai-chat/sessions/" + sessionId + "/messages?userId=" + userId,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<java.util.List<ChatMessageRemote>>>() {
                }, 0L);
        return turns == null ? java.util.List.of() : turns;
    }

    /**
 * The whitelisted pinned context of one session a2 read
 * door, core GET /internal/ai-chat/sessions/{id}/context?userId=): the
 * raw context_json string already validated at the write door — the
 * read-side renderer, not this gateway, decides which keys become facts.
 * Door failures surface to the orchestrator, which drops the pinned
 * section instead of killing the turn.
 */
    public String sessionContext(long userId, long sessionId) {
        String context = call(HttpMethod.GET,
                "/internal/ai-chat/sessions/" + sessionId + "/context?userId=" + userId,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                }, 0L);
        return context == null ? "" : context;
    }

    /** Seal a turn with its terminal status (done/error); returns the turn mirror. */
    public ChatMessageRemote closeChatMessage(long messageId, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        return call(HttpMethod.POST, "/internal/ai-chat/messages/" + messageId + "/close",
                body, new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ChatMessageRemote>>() {
                }, 0L);
    }

    /**
 * Current turn status of one chat message : the
 * ledger face the user's abort command flips. The dialog run loop polls
 * it at its EXISTING checkpoints (round head / batch head), so a click
 * costs at most the in-flight call before new provider launches stop.
 */
    public String chatMessageStatus(long messageId) {
        return call(HttpMethod.GET,
                "/internal/ai-chat/messages/" + messageId + "/status",
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                }, 0L);
    }

    /** Interrupt a streaming turn honestly: partial output kept, window sealed. */
    public ChatMessageRemote abortChatMessage(long messageId) {
        return call(HttpMethod.POST, "/internal/ai-chat/messages/" + messageId + "/abort",
                null, new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ChatMessageRemote>>() {
                }, 0L);
    }

    /**
 * The running worker's steering read door ( package 2 segment B, core
 * GET /internal/ai-chat/sessions/{id}/pending-injects?afterSeq=): user
 * supplements that landed on this streaming turn above the cursor, oldest
 * seq first. A null body reads as "nothing pending" — the poll runs at
 * every round boundary, and a door that momentarily cannot answer must
 * not kill a running turn (the next boundary retries; the frames stay
 * journaled, never dropped).
 */
    public java.util.List<InjectFrameRemote> pendingInjects(long sessionId, long afterSeq) {
        java.util.List<InjectFrameRemote> pending = call(HttpMethod.GET,
                "/internal/ai-chat/sessions/" + sessionId + "/pending-injects?afterSeq=" + afterSeq,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<java.util.List<InjectFrameRemote>>>() {
                }, 0L);
        return pending == null ? java.util.List.of() : pending;
    }

    /**
 * Newest user-image ledger bookings of one session (BE batch
 * read door, core GET /internal/ai-chat/sessions/{id}/chat-images): the
 * raw meta_json strings, newest seq first, at most {@code limit} rows;
 * ownership is re-checked core-side against the calling user. The scanner
 * parses the structured value — this gateway only moves the wire bytes.
 */
    public java.util.List<String> chatImageMetas(long userId, long sessionId, int limit) {
        java.util.List<String> metas = call(HttpMethod.GET,
                "/internal/ai-chat/sessions/" + sessionId + "/chat-images?userId=" + userId
                        + "&limit=" + limit,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<java.util.List<String>>>() {
                }, 0L);
        return metas == null ? java.util.List.of() : metas;
    }

    
    public ToolInvokeResultRemote invokeTool(Long actorId, Long taskId, long toolId, String input) {
        return invokeTool(actorId, taskId, toolId, input, null);
    }

    
    public ToolInvokeResultRemote invokeTool(Long actorId, Long taskId, long toolId, String input,
                                             String confirmToken) {
        return invokeTool(actorId, taskId, toolId, input, confirmToken, null);
    }

    /**
 * Carrier-carried project segment overload : {@code assertedProjectId}
 * is the server-side conversation segment put on the envelope — never inside the model-composed
 * {@code input} — so the core identity guard can trust it as the project fact for system-level
 * tools that carry no project of their own. Null = no anchor (async generation link), where a
 * stamped projectId keeps being refused fail-closed.
 */
    public ToolInvokeResultRemote invokeTool(Long actorId, Long taskId, long toolId, String input,
                                             String confirmToken, Long assertedProjectId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("input", input);
        if (confirmToken != null && !confirmToken.isBlank()) {
            body.put("confirmToken", confirmToken);
        }
        if (assertedProjectId != null && assertedProjectId > 0) {
            body.put("assertedProjectId", assertedProjectId);
        }
        return call(HttpMethod.POST, "/internal/tools/" + toolId + "/invoke", body,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ToolInvokeResultRemote>>() {
                }, actorId == null ? 0L : actorId);
    }

    /**
 * Aggregate query run (AO batch P3 ; core POST
 * /internal/query-spec/run?projectId= — the model-composed QuerySpec rides
 * as raw JSON, every boundary enforced by the core interpreter; the
 * refusal sentences arrive as ServiceException via RestClientInvoker).
 */
    public QueryResultRemote runQuerySpec(Long actorId, long projectId, String specJson) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("spec", parseSpec(specJson));
        return call(HttpMethod.POST, "/internal/query-spec/run?projectId=" + projectId, body,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<QueryResultRemote>>() {
                }, actorId == null ? 0L : actorId);
    }

    /** Callable-target vocabulary . */
    public java.util.List<QueryTargetViewRemote> getQueryTargets(Long actorId) {
        java.util.List<QueryTargetViewRemote> data = call(HttpMethod.GET,
                "/internal/query-spec/targets", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<java.util.List<QueryTargetViewRemote>>>() {
                }, actorId == null ? 0L : actorId);
        return data == null ? java.util.List.of() : data;
    }

    /**
 * Read-only analysis door .
 * The project segment travels as a transport parameter carried by the caller
 * (the conversation's own segment) and the operator rides X-Operator-Id —
 * neither is ever model-supplied; the computed view comes back as a generic
 * map so the dialog side never mirrors core view shapes (zero contract drift).
 * Guard refusals arrive as ServiceException (human-readable) via the
 * RestClientInvoker unwrapping, the same as runQuerySpec.
 */
    public java.util.Map<String, Object> runAnalysis(Long operatorId, long projectId,
                                                     String function, String argsJson) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("function", function);
        body.put("args", readArgsOrNull(argsJson));
        return call(HttpMethod.POST, "/internal/analysis/run?projectId=" + projectId, body,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<java.util.Map<String, Object>>>() {
                }, operatorId == null ? 0L : operatorId);
    }

    /** Blank arguments = whole-project analysis (absent filters, not invalid ones). */
    private static com.fasterxml.jackson.databind.JsonNode readArgsOrNull(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return null;
        }
        return parseSpec(argsJson);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SPEC_READER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode parseSpec(String specJson) {
        try {
            return SPEC_READER.readTree(specJson);
        } catch (Exception e) {
            // A body that is not JSON never reaches the interpreter door —
            // the caller-side guard refuses earlier (AggregateQueryTool), this
            // branch only protects against misuse.
            throw new IllegalArgumentException("查询规格不是合法 JSON（开发期缺陷）", e);
        }
    }

    
    public List<McpAssemblyRemoteServer> getMcpAssemblyServers(Long projectId) {
        String uri = projectId == null
                ? "/internal/mcp-servers/assembly"
                : "/internal/mcp-servers/assembly?projectId=" + projectId;
        List<McpAssemblyRemoteServer> data = call(HttpMethod.GET,
                uri, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<McpAssemblyRemoteServer>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    
    public void recordToolInvocations(List<McpInvocationRemoteInput> invocations) {
        call(HttpMethod.POST, "/internal/mcp-servers/invocations", invocations, null, 0L);
    }

    
    public void writeExtractionResult(long documentId, List<Map<String, Object>> items,
                                      String extractToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("extractToken", extractToken);
        call(HttpMethod.POST, "/internal/documents/" + documentId + "/extraction-result", body, null, 0L);
    }

    
    public void markExtractionFailed(long documentId, String errorMsg, String extractToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorMsg", errorMsg);
        body.put("extractToken", extractToken);
        call(HttpMethod.POST, "/internal/documents/" + documentId + "/extraction-failed", body, null, 0L);
    }

    /**
 * segment B generation self-check read door (core GET
 * /internal/documents/{id}/extract-token). Same posture as
 * {@link #getSysConfig}: any failure (unreachable core, deleted document,
 * empty token) reads as null = "cannot tell" — the extraction pipeline
 * keeps running its own generation on a null and only aborts on a
 * foreign token actually read back. Never surface this value to users:
 * it is internal generation-reconciliation data.
 */
    public String fetchExtractToken(long documentId) {
        try {
            String data = call(HttpMethod.GET, "/internal/documents/" + documentId + "/extract-token",
                    null,
                    new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                    }, 0L);
            return (data == null || data.isBlank()) ? null : data;
        } catch (Exception e) {
            return null;
        }
    }

    
    public void writeSpecDrafts(long documentId, long operatorId, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        call(HttpMethod.POST, "/internal/api-spec-previews/from-drafts/" + documentId,
                body, null, operatorId);
    }

    
    public ExploreSessionBrief getExploreSession(long sessionId) {
        return call(HttpMethod.GET, "/internal/explore/sessions/" + sessionId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ExploreSessionBrief>>() {
                }, 0L);
    }

    
    public ExploreSessionBrief updateExploreStats(long sessionId, ExploreRunStatsUpdate stats) {
        return call(HttpMethod.PUT, "/internal/explore/sessions/" + sessionId + "/stats", stats,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ExploreSessionBrief>>() {
                }, 0L);
    }

    
    public void recordExploreStep(long sessionId, ExploreStepRecord step) {
        call(HttpMethod.POST, "/internal/explore/sessions/" + sessionId + "/steps", step, null, 0L);
    }

    
    public void updateExploreStatus(long sessionId, int status) {
        call(HttpMethod.PUT, "/internal/explore/sessions/" + sessionId + "/status",
                Map.of("status", status), null, 0L);
    }

    
    public long submitExploreAct(long sessionId, String actionJson) {
        Long execTaskId = call(HttpMethod.POST, "/internal/explore/sessions/" + sessionId + "/acts",
                java.util.Map.of("actionJson", actionJson),
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return execTaskId == null ? 0L : execTaskId;
    }

    
    public long submitExploreActStandalone(long projectId, String actionJson) {
        Long execTaskId = call(HttpMethod.POST, "/internal/explore/acts",
                java.util.Map.of("projectId", projectId, "actionJson", actionJson),
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return execTaskId == null ? 0L : execTaskId;
    }

    
    public ExecTaskResultRemote pollExecTaskResult(long execTaskId) {
        return pollExecTaskResult(execTaskId, 120_000L);
    }

    /**
     * Poll body with a caller-derived cap (the deterministic whole-site crawl
     * budgets from its frozen deadline; same discipline — 2s interval,
     * terminal 3..6 stops, any read failure = last known state returned,
     * never thrown). One implementation, both caps.
     */
    public ExecTaskResultRemote pollExecTaskResult(long execTaskId, long capMs) {
        long intervalMs = 2000L;
        long deadline = System.currentTimeMillis() + capMs;
        ExecTaskResultRemote last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                last = getExecTaskResult(execTaskId);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(CoreGateway.class)
                        .warn("执行任务读路径查询失败 execTaskId={}: {}", execTaskId, e.getMessage());
                return last;
            }
            if (last != null && last.status() >= 3) {
                return last;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        org.slf4j.LoggerFactory.getLogger(CoreGateway.class)
                .warn("执行任务读路径等待超时 execTaskId={}（按最后已知态回填）", execTaskId);
        return last;
    }

    
    public ExecTaskResultRemote getExecTaskResult(long taskId) {
        return call(HttpMethod.GET, "/internal/executors/tasks/" + taskId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ExecTaskResultRemote>>() {
                }, 0L);
    }

    /**
 * Whole-site survey rules door : core GET
 * /internal/site-survey-runs/by-session/{sessionId}. Wire tolerance: the
 * door answers the snapshot either bare ({@code {"rulesJson": ...}}) or
 * Response-wrapped ({@code {"code":"0","data":{"rulesJson": ...}}}); a
 * non-zero business code (40401 = the session carries no run row) reads
 * as null so the kernel can stop honestly BEFORE dispatching a blind
 * crawl. Transport failures propagate as runtime exceptions — same
 * fail-soft expectation as every read door here, the caller degrades to
 * a partial terminal.
 */
    public String getSiteSurveyRulesBySession(long sessionId) {
        com.fasterxml.jackson.databind.JsonNode body = rest.get()
                .uri(java.net.URI.create("/internal/site-survey-runs/by-session/" + sessionId))
                .retrieve()
                .body(com.fasterxml.jackson.databind.JsonNode.class);
        if (body == null) {
            return null;
        }
        String code = body.path("code").asText("0");
        if (!"0".equals(code)) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode holder =
                body.hasNonNull("rulesJson") ? body : body.path("data");
        return holder.path("rulesJson").asText(null);
    }

    
    public ExploreGrantBrief grantExploreEvidence(long sessionId, String kind, String fileName) {
        String query = "?kind=" + java.net.URLEncoder.encode(kind == null ? "" : kind,
                java.nio.charset.StandardCharsets.UTF_8)
                + "&fileName=" + java.net.URLEncoder.encode(fileName == null ? "" : fileName,
                java.nio.charset.StandardCharsets.UTF_8);
        return call(HttpMethod.GET, "/internal/explore/sessions/" + sessionId + "/evidence-grant" + query,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<ExploreGrantBrief>>() {
                }, 0L);
    }

    /**
 * tool-result upload grant (core GET
 * /internal/ai-chat/tool-artifact/grant?fileName=, system-level presigned
 * PUT). A refused grant surfaces as ServiceException via
 * RestClientInvoker — callers let it reach the budget folder's single
 * fail-soft point instead of claiming a stored anchor.
 */
    public com.sixbean.testpike.aiservice.tool.infrastructure.remote.ToolArtifactGrantBrief
            grantToolArtifactUpload(String fileName) {
        String query = "?fileName=" + java.net.URLEncoder.encode(fileName == null ? "" : fileName,
                java.nio.charset.StandardCharsets.UTF_8);
        return call(HttpMethod.GET, "/internal/ai-chat/tool-artifact/grant" + query, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        com.sixbean.testpike.aiservice.tool.infrastructure.remote.ToolArtifactGrantBrief>>() {
                }, 0L);
    }

    /**
 * ③ tool-result view grant (core GET
 * /internal/ai-chat/tool-artifact/view?objectKey= — presigned GET url
 * resolved on the same ArtifactStorage route the upload used, keeping
 * write and read on one storage door). A refused grant surfaces as
 * ServiceException via RestClientInvoker for the retrieval meta-tool.
 */
    public String grantToolArtifactView(String objectKey) {
        String query = "?objectKey=" + java.net.URLEncoder.encode(
                objectKey == null ? "" : objectKey, java.nio.charset.StandardCharsets.UTF_8);
        return call(HttpMethod.GET, "/internal/ai-chat/tool-artifact/view" + query, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                }, 0L);
    }

    
    public List<Map<String, Object>> getCaseReviewLogs(long caseId) {
        List<Map<String, Object>> data = call(HttpMethod.GET, "/internal/cases/" + caseId + "/review-logs", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    /**
 * Case execution history : core
 * existing GET /internal/executors/cases/{caseId}/history (J5 door, H1
 * segment re-checked core-side against projectId) mapped to its wire shape
 * — PageResult fields list/total, rows are CaseExecHistoryView. Callers
 * treat a thrown ServiceException/RuntimeException as "unreadable" and
 * degrade the digest line, never the turn.
 */
    public Map<String, Object> getCaseExecHistory(long caseId, long projectId, int page, int size) {
        return call(HttpMethod.GET, "/internal/executors/cases/" + caseId + "/history"
                + "?projectId=" + projectId + "&page=" + page + "&size=" + size, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, 0L);
    }

    
    public List<Map<String, Object>> listCaseVersions(long caseId) {
        List<Map<String, Object>> data = call(HttpMethod.GET, "/internal/cases/" + caseId + "/versions", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    
    public Map<String, Object> getCaseVersionDiff(long caseId, int version, int baseVersion) {
        return call(HttpMethod.GET, "/internal/cases/" + caseId + "/versions/" + version
                + "/diff?baseVersion=" + baseVersion, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, 0L);
    }

    
    public void aiClassify(long taskId, int aiFailureClass, String aiFailureNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aiFailureClass", aiFailureClass);
        body.put("aiFailureNote", aiFailureNote);
        call(HttpMethod.POST, "/internal/exec-tasks/" + taskId + "/ai-classify", body, null, 0L);
    }

    
    public List<Map<String, Object>> listRecentClassify(long caseId) {
        List<Map<String, Object>> data = call(HttpMethod.GET,
                "/internal/exec-tasks/case/" + caseId + "/recent-classify", null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    
    public String screenshotUrl(long taskId, String objectKey) {
        String uri = "/internal/exec-tasks/" + taskId + "/screenshot-url?objectKey="
                + java.net.URLEncoder.encode(objectKey, java.nio.charset.StandardCharsets.UTF_8);
        return call(HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<String>>() {
                }, 0L);
    }

    
    public List<WordingPair> wordingPairs(long projectId, int limit) {
        List<WordingPair> data = call(HttpMethod.GET,
                "/internal/training-samples/wording-pairs?projectId=" + projectId
                        + "&limit=" + limit, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<WordingPair>>>() {
                }, 0L);
        return data == null ? List.of() : data;
    }

    
    public PromptTemplateBrief promptTemplateLatestByStatus(String name, String domain, int status) {
        String uri = "/internal/prompt-templates/latest-by-status?name="
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                + "&domain=" + java.net.URLEncoder.encode(domain, java.nio.charset.StandardCharsets.UTF_8)
                + "&status=" + status;
        return call(HttpMethod.GET, uri, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<PromptTemplateBrief>>() {
                }, 0L);
    }

    
    public PromptTemplateBrief promptTemplateDetail(long templateVersionId) {
        return call(HttpMethod.GET, "/internal/prompt-templates/" + templateVersionId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<PromptTemplateBrief>>() {
                }, 0L);
    }

    /**
     * Golden replay verdict landed on the core drift ledger
     * (core POST /internal/behavior/golden-drift/verdict). The gateway ships
     * normalized pair digests only — the drift rate is re-derived server-side,
     * never trusted from the caller. Business errors propagate (a silently
     * dropped verdict would leave a version without an admission ticket).
     */
    public boolean postGoldenDriftVerdict(Map<String, Object> body) {
        Boolean landed = call(HttpMethod.POST, "/internal/behavior/golden-drift/verdict", body,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Boolean>>() {
                }, 0L);
        return Boolean.TRUE.equals(landed);
    }

    
    public Long getTaskTemplatePin(long taskId) {
        AiTaskPinView view = call(HttpMethod.GET, "/internal/ai-tasks/" + taskId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<AiTaskPinView>>() {
                }, 0L);
        return view == null ? null : view.templateVersionId();
    }

    
    public void pinTemplate(long taskId, long templateVersionId) {
        call(HttpMethod.PUT, "/internal/ai-tasks/" + taskId + "/template-pin",
                java.util.Map.of("templateVersionId", templateVersionId), null, 0L);
    }

    
    public RepairTaskView getRepairTaskView(long taskId) {
        return call(HttpMethod.GET, "/internal/executors/tasks/" + taskId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<RepairTaskView>>() {
                }, 0L);
    }

    /**
 * Screenshot artifact list : reuses core's existing GET
 * /internal/executors/tasks/{id}/screenshots (F-8 door, objectKey →
 * presigned GET url projection). The dialog vision seam reads through it
 * instead of touching S3 — storage routing and the ownership gate stay
 * single-sourced in core. Empty list = the task carries no screenshots;
 * door errors surface as ServiceException for the caller to degrade
 * honestly (never breaks the text-only answer).
 */
    public java.util.List<com.sixbean.testpike.aiservice.aichat.infrastructure.remote.TaskScreenshotView>
            taskScreenshots(long taskId) {
        java.util.List<com.sixbean.testpike.aiservice.aichat.infrastructure.remote.TaskScreenshotView>
                data = call(HttpMethod.GET, "/internal/executors/tasks/" + taskId + "/screenshots",
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        java.util.List<com.sixbean.testpike.aiservice.aichat
                                .infrastructure.remote.TaskScreenshotView>>>() {
                }, 0L);
        return data == null ? java.util.List.of() : data;
    }

    /**
 * Step evidence rows for the dialog replay context : reuses
 * core's existing GET /internal/executors/tasks/{id}/step-evidence door
 * (presigned six-slot evidence projection). Presence read only; door
 * errors surface as ServiceException for the caller to degrade honestly.
 */
    public java.util.List<com.sixbean.testpike.aiservice.aichat
            .infrastructure.remote.ExecStepEvidenceRemote> taskStepEvidence(long taskId) {
        java.util.List<com.sixbean.testpike.aiservice.aichat
                .infrastructure.remote.ExecStepEvidenceRemote> data =
                call(HttpMethod.GET, "/internal/executors/tasks/" + taskId + "/step-evidence",
                        null,
                        new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                                java.util.List<com.sixbean.testpike.aiservice.aichat
                                        .infrastructure.remote.ExecStepEvidenceRemote>>>() {
                        }, 0L);
        return data == null ? java.util.List.of() : data;
    }

    /**
 * Presigned GET url for one dialog user-image key :
 * reads through core's chat-image door
 * (GET /internal/ai-chat/images/url), the SINGLE ownership point of the
 * channel — shape, project segment and object presence are all enforced in
 * core before a url is minted, so the vision leg never touches object
 * storage itself and a cross-project key surfaces as a ServiceException
 * the resolver degrades honestly.
 */
    public String chatImageUrl(long projectId, String objectKey) {
        return call(HttpMethod.GET, "/internal/ai-chat/images/url?projectId=" + projectId
                + "&objectKey=" + java.net.URLEncoder.encode(objectKey,
                        java.nio.charset.StandardCharsets.UTF_8),
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        String>>() {
                }, 0L);
    }

    /**
 * Stage freshly parsed screenshot credential rows into core's transient
 * capture slot . The answer is the staging token
 * only — nothing value-bearing echoes back through this door (piece E red
 * line). Rows carry the CaptureCommittedEntry wire shape (kind/name/origin/
 * value/expiresAtEpochSecond); the channel literal mirrors core's
 * CaptureSlotStageInput constant, mapped in ScreenshotLoginStateStager.
 */
    public String stageScreenshotSlot(String channel, long sessionId,
                                      List<Map<String, Object>> entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("sessionId", sessionId);
        body.put("entries", entries);
        return call(HttpMethod.POST, "/internal/session-capture-sessions/stage", body,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        String>>() {
                }, 0L);
    }

    /**
 * Name-domain probe of one screenshot staging slot : the
 * draft emitter rewrites every card row onto these staged names before
 * minting, so the landing door's fail-closed per-row check can never
 * meet a model-transcribed lookalike. Names only — values never cross
 * this door (piece E red line). Callers fail-soft: an empty answer or a
 * door exception both end in an honest card refusal upstream.
 */
    public List<String> getStagedLoginStateNames(String token) {
        List<String> names = call(HttpMethod.GET,
                "/internal/session-capture-sessions/stage/"
                        + java.net.URLEncoder.encode(token == null ? "" : token,
                                java.nio.charset.StandardCharsets.UTF_8)
                        + "/names",
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        List<String>>>() {
                }, 0L);
        return names == null ? List.of() : names;
    }

    /**
 * Narrow script read for the dialog repair draft : reuses
 * core's existing GET /internal/scripts/{caseId}; the current action JSON
 * is the diff "before" and the version-line number is the optimistic-lock
 * anchor knife 3b re-checks before anything lands. Missing script surfaces
 * as ServiceException (40401) — the caller degrades honestly.
 */
    public ScriptView getScript(long caseId) {
        return call(HttpMethod.GET, "/internal/scripts/" + caseId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        ScriptView>>() {
                }, 0L);
    }

    /**
 * : land one linkage proposal snapshot on the core ledger (POST
 * /internal/linkage-proposals, tp_linkage_proposal). Idempotent by
 * impact_id core-side (identical replay writes nothing, consumed rows stay
 * frozen); the ack map (created/status) is returned for honest logging —
 * a proposal leg that cannot persist its outcome must fail loudly so the
 * container retries instead of acking into silence.
 */
    @SuppressWarnings("unchecked")
    public java.util.Map<String, Object> postLinkageProposal(java.util.Map<String, Object> body) {
        return call(HttpMethod.POST, "/internal/linkage-proposals", body,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        java.util.Map<String, Object>>>() {
                }, 0L);
    }

    
    public long createRepairTrialTask(long sourceTaskId, String schemaVersion, String actionJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceTaskId", sourceTaskId);
        body.put("schemaVersion", schemaVersion);
        body.put("actionJson", actionJson);
        Long taskId = call(HttpMethod.POST, "/internal/script-repairs/trial-task", body,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return taskId == null ? 0L : taskId;
    }

    
    public void saveRepairedScript(long caseId, String schemaVersion, String actionJson) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", schemaVersion);
        body.put("actionJson", actionJson);
        call(HttpMethod.POST, "/internal/scripts/" + caseId + "/repaired", body, null, 0L);
    }

    
    public void recordScriptRepair(long projectId, long caseId, Long sourceTaskId,
                                   Long trialTaskId, String proposalSummary,
                                   int outcome, String failNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", projectId);
        body.put("caseId", caseId);
        body.put("sourceTaskId", sourceTaskId);
        body.put("trialTaskId", trialTaskId);
        body.put("proposalSummary", proposalSummary);
        body.put("outcome", outcome);
        body.put("failNote", failNote);
        call(HttpMethod.POST, "/internal/script-repairs", body, null, 0L);
    }

    
    public long repairRounds(long caseId) {
        Long rounds = call(HttpMethod.GET, "/internal/script-repairs/rounds?caseId=" + caseId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Long>>() {
                }, 0L);
        return rounds == null ? 0L : rounds;
    }

    /**
 * Repair-ledger rows of one case over the existing core
 * GET /internal/script-repairs door ( S2 fold-back read; newest first,
 * page shape {list:[...], total:n}). A missing page or an absent list
 * surfaces as an empty list — callers keep the fail-soft posture and the
 * generation prompt falls back to its legacy bytes.
 */
    public List<Map<String, Object>> listCaseRepairs(long caseId, int size) {
        Map<String, Object> page = call(HttpMethod.GET,
                "/internal/script-repairs?caseId=" + caseId + "&page=1&size=" + Math.max(1, size),
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, 0L);
        if (page == null || !(page.get("list") instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) row;
                    return cast;
                })
                .toList();
    }

    /**
 * Project data-factory rows the generated steps may reference ( S2
 * segment B resource-pool segment; core EXISTING GET /internal/data-factories
 * door — page shape {list,total}). The task initiator rides X-Operator-Id
 * so the door is never an anonymous internal read (transparency posture
 * shared with runAnalysis). Read-path failures
 * surface as ServiceException from RestClientInvoker; callers fail-soft.
 */
    public List<Map<String, Object>> listProjectDataFactories(long projectId, long operatorId) {
        Map<String, Object> page = call(HttpMethod.GET,
                "/internal/data-factories?projectId=" + projectId + "&page=1&size=100",
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<Map<String, Object>>>() {
                }, operatorId);
        if (page == null || !(page.get("list") instanceof List<?> rows)) {
            return List.of();
        }
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) row;
                    return cast;
                })
                .toList();
    }

    /**
 * Project API-collection rows the generated steps may reference ( S2
 * segment B; core EXISTING GET /internal/apitest/collections door returning
 * the collection forest for one project, zero core change). Callers
 * fail-soft.
 */
    public List<Map<String, Object>> listProjectApiCollections(long projectId, long operatorId) {
        List<Map<String, Object>> data = call(HttpMethod.GET,
                "/internal/apitest/collections?projectId=" + projectId,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<List<Map<String, Object>>>>() {
                }, operatorId);
        return data == null ? List.of() : data;
    }

    // ---- AI mock drafting doors ; core MockAiJobInternalController
    // is the server side — the consumer pulls the job row and seals transitions
    // over these doors instead of trusting a fat MQ payload) ----

    /** Pull one drafting job (ghost row arrives as null: core refuses a deleted id
     *  with a plain-word ServiceException whose code the caller distinguishes). */
    public com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockAiJobInfoRemote
            getMockJob(long jobId) {
        return call(HttpMethod.GET, "/internal/mock/jobs/" + jobId, null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockAiJobInfoRemote>>() {
                }, 0L);
    }

    /** Seal queued→running (a refused transition throws — the losing concurrent
     *  consumer treats the refusal as "someone else is on it" and acks silently). */
    public void markMockRunning(long jobId) {
        call(HttpMethod.POST, "/internal/mock/jobs/" + jobId + "/status",
                java.util.Map.of("status", "running"), null, 0L);
    }

    /** Seal running→preview with the artifact reference (object key, never the payload). */
    public void markMockPreview(long jobId, String resultPreviewRef) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "preview");
        body.put("resultPreviewRef", resultPreviewRef);
        call(HttpMethod.POST, "/internal/mock/jobs/" + jobId + "/status", body, null, 0L);
    }

    /** Seal running→failed with a plain-word reason (the failure story of the job). */
    public void markMockFailed(long jobId, String errorMessage) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "failed");
        body.put("errorMessage", errorMessage);
        call(HttpMethod.POST, "/internal/mock/jobs/" + jobId + "/status", body, null, 0L);
    }

    /** Ask the draft-bundle upload grant (presigned PUT; explore evidence-grant
     *  precedent — core routes the key to the project bucket, no s3 dependency here). */
    public com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockDraftArtifactGrantBrief
            grantMockDraftArtifact(long jobId, String fileName) {
        String query = "?fileName=" + java.net.URLEncoder.encode(
                fileName == null ? "" : fileName, java.nio.charset.StandardCharsets.UTF_8);
        return call(HttpMethod.GET, "/internal/mock/jobs/" + jobId + "/artifact-grant" + query,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockDraftArtifactGrantBrief>>() {
                }, 0L);
    }

    /**
 * Case one freshly paid A-3 fallback generation into the core cache
 * ; core MockLlmFallbackInternalController /store is the
 * server side). The store door re-runs the dual gates and the budget —
 * this leg never re-checks them (single-sourced spectrum) and relays
 * core's honest verdict back untouched.
 */
    public MockLlmFallbackStoreBrief storeMockLlmFallback(MockLlmFallbackStoreInput input) {
        return call(HttpMethod.POST, "/internal/mock/llm-fallback/store", input,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        MockLlmFallbackStoreBrief>>() {
                }, 0L);
    }

    /**
 * Pull the masked frame SAMPLE of one cassette box for the A-4 induction
 * leg ; core MockRuleInternalController /recording/frames is
 * the server side). The newest-N cap and the box-state honesty (live hold
 * vs sealed cassette) are core semantics — this leg consumes whatever the
 * door answers and never asks for more than the K8 sample.
 */
    public List<com.sixbean.testpike.common.mq.MockRecordingFrameMessage>
            getMockRecordingFrames(long recordingId) {
        return call(HttpMethod.GET, "/internal/mock/recording/frames?recordingId=" + recordingId,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<
                        List<com.sixbean.testpike.common.mq.MockRecordingFrameMessage>>>() {
                }, 0L);
    }

    public <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type,
                       long operatorId) {
        Long op = operatorId > 0 ? operatorId : null;
        if (type == null) {
            return RestClientInvoker.call(rest, method, uri, body,
                    new ParameterizedTypeReference<>() {
                    }, op);
        }
        return RestClientInvoker.call(rest, method, uri, body, type, op);
    }

    /**
 * Latest contract rows of one project the assertion
 * truth-source read). Wire shape = core PageResult {list,total};
 * ApiSpecBrief mirrors the ApiSpecInfo line names the bridge consumes
 * (unknown fields ignored, ProjectBrief precedent). Callers fail-soft.
 */
    public PageRows<ApiSpecBrief> listApiSpecs(long projectId, int page, int size) {
        PageRows<ApiSpecBrief> data = call(HttpMethod.GET,
                "/internal/api-specs?projectId=" + projectId + "&page=" + page
                        + "&size=" + size,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<PageRows<ApiSpecBrief>>>() {
                }, 0L);
        return data == null ? new PageRows<>(List.of(), 0) : data;
    }

    /**
     * Project-wide mock serving inventory page (measured response samples with
     * 7-day hit counts; core MockRuleInternalController GET
     * /internal/mock/serving-inventory/page — the wire row is a
     * MockServingEndpointView subset). Callers fail-soft.
     */
    public PageRows<MockServingBrief> listMockServingInventory(long projectId, int page,
                                                               int size) {
        PageRows<MockServingBrief> data = call(HttpMethod.GET,
                "/internal/mock/serving-inventory/page?projectId=" + projectId
                        + "&page=" + page + "&pageSize=" + size,
                null,
                new ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<PageRows<MockServingBrief>>>() {
                }, 0L);
        return data == null ? new PageRows<>(List.of(), 0) : data;
    }

    /** Paged window of a core internal list endpoint ({list,total} wire shape). */
    public record PageRows<T>(List<T> list, long total) {
    }

    /** Contract version row brief (display name + latest version + endpoint snapshot). */
    public record ApiSpecBrief(Long id, String name, Integer specVersion,
                               List<ApiEndpointBrief> endpoints) {
    }

    /** One endpoint line of a contract snapshot (parameters + response schema text). */
    public record ApiEndpointBrief(String method, String path,
                                   List<ApiParamBrief> parameters,
                                   Map<String, String> responseSchemas) {
    }

    /** Parameter skeleton: name only (the bridge cites field names, not types). */
    public record ApiParamBrief(String name) {
    }

    /** Measured mock row: served sample payload + its 7-day matched-serve count. */
    public record MockServingBrief(Long stubId, String method, String pathPattern,
                                   String responseJson, Long hits7d) {
    }

 
    public record ProjectBrief(Long id, String code, String defaultLang, Integer kbEnabled,
                               Integer classifyVision, Long testOwnerId,
                               Integer pageInspectEnabled) {

 
        public ProjectBrief(Long id, String code, String defaultLang, Integer kbEnabled) {
            this(id, code, defaultLang, kbEnabled, null, null, null);
        }

 
        public ProjectBrief(Long id, String code, String defaultLang, Integer kbEnabled,
                            Integer classifyVision) {
            this(id, code, defaultLang, kbEnabled, classifyVision, null, null);
        }

 
        public ProjectBrief(Long id, String code, String defaultLang, Integer kbEnabled,
                            Integer classifyVision, Long testOwnerId) {
            this(id, code, defaultLang, kbEnabled, classifyVision, testOwnerId, null);
        }
    }

    
    public record EnvBrief(Long id, Integer envType, String baseUrl, String name) {
    }

    /**
 * Field-subset mirror of core DbConnectionInfo for the name door only —
 * jdbcUrl/username/password have no representation on this side by design
 */
    public record DbConnectionBrief(long envId, String name, int status) {
    }

    /**
 * Help chapter subset for the dialog grounding source (AO batch ;
 * mirrors core {@code HelpDocInfo} line names — unknown fields ignored,
 * precedent = EnvBrief vs ProjectEnvInfo).
 *
 * @param bodyMd null on index rows, full Markdown on detail rows
 * @param domainCode functional domain letter used as the UI anchor
 */
    public record HelpDocBrief(Long id, String domainCode, String title, String bodyMd,
                               int sortNo) {
    }

    
    public record RequirementBrief(Long id, String title, String acceptanceCriteria,
                                   SourceAnchor sourceAnchor) {

        /** Legacy three-arg shape (no anchor column exposure): call sites written
 * before keep compiling with sourceAnchor = null = unanchored. */
        public RequirementBrief(Long id, String title, String acceptanceCriteria) {
            this(id, title, acceptanceCriteria, null);
        }
    }

    /** Evidence anchor mirrored from core RequirementSourceAnchor (same line shape). */
    public record SourceAnchor(Long documentId, List<Long> chunkIds, String evidence) {
    }

 
    public record PromptTemplateBrief(Long id, String name, String domain, Integer version,
                                      String content, Integer status) {
    }

 
    public record AiTaskPinView(Long templateVersionId) {
    }

 
    public record WordingPair(Long rejectSampleId, Long diffSampleId, String remark,
                              String diffChangesJson, String createdAt) {
    }
}
