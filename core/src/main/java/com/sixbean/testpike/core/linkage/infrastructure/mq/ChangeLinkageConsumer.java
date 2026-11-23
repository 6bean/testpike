package com.sixbean.testpike.core.linkage.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.linkage.LinkageImpactInfo;
import com.sixbean.testpike.core.linkage.api.dto.LinkageImpactSummary;
import com.sixbean.testpike.core.linkage.api.dto.LinkageProposalInput;
import com.sixbean.testpike.common.mq.LinkageProposalRequestMessage;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage;
import com.sixbean.testpike.core.linkage.domain.component.ContractSpecFoldedKeys;
import com.sixbean.testpike.core.linkage.domain.service.ChangeLinkageDispatchService;
import com.sixbean.testpike.core.linkage.domain.service.LinkageProposalRequestPublisher;
import com.sixbean.testpike.core.linkage.domain.service.LinkageProposalService;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Core consumer of the generic object-changed family — the single entry that
 * turns an upstream save announcement into downstream edge detection. One
 * listener serves every bus member: the edge registry, not scattered hooks,
 * decides what a save touches.
 * <p>After detection, every draft armed by this pass (grouped-draft rows newly
 * written or re-armed by a newer version) is relayed to its producer. Script
 * drafts go to the reused repair-proposal generator in the AI service; the
 * contract-side members (derived stub, imported request) are produced
 * deterministically right here from the new spec version's folded endpoint
 * keys — no guessed generator. Any other draft shape stays pending on the
 * ledger for its own batch, with a debug line, never a silent one.
 * <p>Duplicate announcements are harmless by design (the impact ledger is
 * keyed by edge + downstream row and versioned), so no consume-lock is spent
 * here; business exceptions propagate so the container retries and then
 * dead-letters instead of silently acking a broken detection pass — the
 * daily bus-vs-edge reconcile job backstops anything that lands in the DLQ.
 */
@Component
public class ChangeLinkageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChangeLinkageConsumer.class);

    /** Downstream member with an in-place proposal producer (script repair chain). */
    private static final String RELAY_MEMBER_SCRIPT = "script";

    /** Contract-side members relayed by the deterministic snapshot producer. */
    private static final String RELAY_MEMBER_MOCK_STUB = "mock_stub";
    private static final String RELAY_MEMBER_APITEST_REQUEST = "apitest_request";

    /** Re-approval member relayed by the perf snapshot producer (AN ticket 4b). */
    private static final String RELAY_MEMBER_PERF_APPROVAL = "perf_approval";

    /** Upstream bus member whose version row carries the folded endpoint keys. */
    private static final String UPSTREAM_CONTRACT = "api_spec";

    /** Upstream bus member whose edit overtakes a decided approval ticket. */
    private static final String UPSTREAM_PERF_SCENARIO = "perf_scenario";

    /** Approval ticket states (common.approval ApprovalStatus). */
    private static final int APPROVAL_PENDING = 2;
    private static final int APPROVAL_APPROVED = 3;

    /** Snapshot identity of the deterministic producer (no AI model involved). */
    private static final String DETERMINISTIC_SCHEMA_VERSION = "v2";
    private static final String DETERMINISTIC_MODEL_NAME = "contract-snapshot";

    private final ChangeLinkageDispatchService dispatchService;
    private final LinkageProposalService proposalService;
    private final LinkageProposalRequestPublisher proposalRequestPublisher;
    private final ContractSpecFoldedKeys contractFoldedKeys;
    private final ContractMatchKeyRules matchKeyRules;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public ChangeLinkageConsumer(ChangeLinkageDispatchService dispatchService,
                                 LinkageProposalService proposalService,
                                 LinkageProposalRequestPublisher proposalRequestPublisher,
                                 ContractSpecFoldedKeys contractFoldedKeys,
                                 ContractMatchKeyRules matchKeyRules,
                                 ObjectMapper objectMapper,
                                 JdbcTemplate jdbc) {
        this.dispatchService = dispatchService;
        this.proposalService = proposalService;
        this.proposalRequestPublisher = proposalRequestPublisher;
        this.contractFoldedKeys = contractFoldedKeys;
        this.matchKeyRules = matchKeyRules;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @RabbitListener(queues = ChangeLinkageQueueConfig.QUEUE_NAME)
    public void onObjectChanged(ObjectChangedEventMessage message) {
        if (message == null || message.objectId() == null) {
            log.warn("linkage consumer received an announcement without objectId — "
                    + "rejected into the dead letter, never silently acked");
            throw new IllegalArgumentException(
                    "object-changed announcement without objectId cannot be dispatched");
        }
        LinkageImpactSummary summary = dispatchService.detectDownstream(message);
        log.info("linkage dispatch eventType={} objectId={} version={} edges={} found={} "
                        + "created={} updated={} drafts={}",
                message.eventType(), message.objectId(), message.objectVersion(),
                summary.edgesChecked(), summary.downstreamFound(),
                summary.impactsCreated(), summary.impactsUpdated(),
                summary.pendingDrafts().size());
        relayProposalRequests(message, summary.pendingDrafts());
    }

    private void relayProposalRequests(ObjectChangedEventMessage message, List<String> drafts) {
        String upstreamType = objectTypeToken(message.eventType());
        for (String draft : drafts) {
            int sep = draft.indexOf(':');
            String type = draft.substring(0, sep);
            long downstreamId = Long.parseLong(draft.substring(sep + 1));
            if (!RELAY_MEMBER_SCRIPT.equals(type)
                    && !isContractDeterministic(type, upstreamType)
                    && !isPerfReapprovalDeterministic(type, upstreamType)) {
                log.debug("linkage draft {} has no proposal producer yet — stays pending on the ledger",
                        draft);
                continue;
            }
            Long impactId = latestImpactId(type, downstreamId, upstreamType, message);
            LinkageImpactInfo impact = impactId == null ? null : proposalService.impactOf(impactId);
            if (impact == null) {
                log.warn("linkage draft {} armed but its impact row is unreadable —"
                        + " reconcile job will surface the missing proposal request", draft);
                continue;
            }
            if (RELAY_MEMBER_SCRIPT.equals(type)) {
                proposalRequestPublisher.publish(new LinkageProposalRequestMessage(
                        impact.id(), impact.edgeKey(), upstreamType,
                        message.objectId(), message.objectVersion(), type, downstreamId,
                        message.projectId(), System.currentTimeMillis()));
            } else if (RELAY_MEMBER_PERF_APPROVAL.equals(type)) {
                recordPerfReapprovalSnapshot(impact, downstreamId, message.objectId());
            } else {
                recordContractDeterministicSnapshot(impact, type, downstreamId, message.objectId());
            }
        }
    }

    /**
     * Contract-side members whose proposal snapshot is fully deterministic:
     * the new spec version's folded endpoint keys plus the member's own rows
     * decide everything, so the consumer produces the snapshot in place
     * instead of waiting on the AI generator (AN ticket 4a).
     */
    private static boolean isContractDeterministic(String type, String upstreamType) {
        return UPSTREAM_CONTRACT.equals(upstreamType)
                && (RELAY_MEMBER_MOCK_STUB.equals(type) || RELAY_MEMBER_APITEST_REQUEST.equals(type));
    }

    /**
     * Re-approval member whose snapshot is fully deterministic: the affected
     * approval ticket plus the scenario's current row decide everything, so
     * the consumer files it in place instead of leaving the draft pending
     * with nothing to accept (AN ticket 4b).
     */
    private static boolean isPerfReapprovalDeterministic(String type, String upstreamType) {
        return UPSTREAM_PERF_SCENARIO.equals(upstreamType)
                && RELAY_MEMBER_PERF_APPROVAL.equals(type);
    }

    /**
     * Deterministic re-approval producer. The ticket's decided shape is
     * re-read together with the scenario's CURRENT environment: only an
     * approved ticket (status 3) is overtaken by a later scenario edit and
     * earns one re-approval card. A still-pending ticket is judged by the
     * approver on the live shape — stacking a second request under it would
     * duplicate the queue; a rejected ticket's re-filing stays a deliberate
     * human act on the approval page. Every refusal is journaled with its
     * plain reason so the ledger withdraws the draft-pending claim honestly.
     */
    private void recordPerfReapprovalSnapshot(LinkageImpactInfo impact, long approvalId,
                                              Long scenarioId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.status, a.requested_concurrency, a.isolation_mode, a.scenario_id,"
                        + " s.env_id AS scenario_env_id"
                        + " FROM tp_perf_approval a"
                        + " LEFT JOIN tp_perf_scenario s ON s.id = a.scenario_id"
                        + " WHERE a.id = ?", approvalId);
        if (rows.isEmpty()) {
            rejectDeterministic(impact, RELAY_MEMBER_PERF_APPROVAL, approvalId,
                    "该审批单行已被删除（审批单 #" + approvalId + "），没有可重审的审批历史");
            return;
        }
        Map<String, Object> ticket = rows.get(0);
        int status = ((Number) ticket.get("status")).intValue();
        if (status == APPROVAL_PENDING) {
            rejectDeterministic(impact, RELAY_MEMBER_PERF_APPROVAL, approvalId,
                    "该审批单仍在待审批中，场景修改后由审批人按现行形状判定，不重复出重审草案");
            return;
        }
        if (status != APPROVAL_APPROVED) {
            rejectDeterministic(impact, RELAY_MEMBER_PERF_APPROVAL, approvalId,
                    "该审批单未处于已通过状态（审批单 #" + approvalId + "），"
                            + "是否重新申请由人工在压测审批页发起，不自动生成重审草案");
            return;
        }
        Object scenarioEnv = ticket.get("scenario_env_id");
        if (scenarioEnv == null) {
            rejectDeterministic(impact, RELAY_MEMBER_PERF_APPROVAL, approvalId,
                    "该审批单挂接的场景行已被删除，无法按现行形状重新申请审批");
            return;
        }
        long linkedScenarioId = ticket.get("scenario_id") == null
                ? scenarioId : ((Number) ticket.get("scenario_id")).longValue();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("scenarioId", linkedScenarioId);
        payload.put("envId", ((Number) scenarioEnv).longValue());
        payload.put("requestedConcurrency", ticket.get("requested_concurrency"));
        payload.put("isolationMode", ticket.get("isolation_mode"));
        payload.put("reason", "联动重审：压测场景 #" + linkedScenarioId
                + " 在上次审批后被修改，按当前形状重新申请审批");
        proposalService.record(new LinkageProposalInput(impact.id(),
                RELAY_MEMBER_PERF_APPROVAL, approvalId,
                writeJson(payload), DETERMINISTIC_SCHEMA_VERSION,
                "perf-reapproval-snapshot", null, null));
    }

    private Long latestImpactId(String type, long downstreamId, String upstreamType,
                                ObjectChangedEventMessage message) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM tp_linkage_impact WHERE downstream_object_type = ?"
                        + " AND downstream_id = ? AND upstream_object_type = ?"
                        + " AND upstream_object_id = ? AND upstream_version = ?"
                        + " ORDER BY id DESC LIMIT 1",
                Long.class, type, downstreamId, upstreamType, message.objectId(),
                message.objectVersion() == null ? 0 : message.objectVersion());
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Deterministic producer for contract fan members. The member's match key
     * (folded through the single ContractMatchKeyRules source, never a local
     * re-implementation) is compared against the new spec version's folded
     * keys: a hit files the snapshot the write strategy lands, and a miss is
     * recorded as an honest rejection with a plain reason, which withdraws
     * the draft-pending claim on the ledger instead of leaving it ignorable
     * forever.
     */
    private void recordContractDeterministicSnapshot(LinkageImpactInfo impact, String type,
                                                     long downstreamId, Long specId) {
        List<String> armedKeys = contractFoldedKeys.armedKeys(specId)
                .map(ContractSpecFoldedKeys.SpecFanKeys::foldedKeys)
                .orElse(List.of());
        if (RELAY_MEMBER_MOCK_STUB.equals(type)) {
            recordStubSnapshot(impact, downstreamId, armedKeys);
        } else {
            recordRequestSnapshot(impact, downstreamId, armedKeys);
        }
    }

    private void recordStubSnapshot(LinkageImpactInfo impact, long stubId, List<String> armedKeys) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, method, path_pattern, version FROM tp_mock_stub WHERE id = ?", stubId);
        if (rows.isEmpty()) {
            rejectDeterministic(impact, "mock_stub", stubId,
                    "该派生桩行已被删除（桩 #" + stubId + "），上游契约新版本没有可更新的桩");
            return;
        }
        Map<String, Object> stub = rows.get(0);
        String key = matchKeyRules.matchKey(String.valueOf(stub.get("method")),
                String.valueOf(stub.get("path_pattern")));
        if (!armedKeys.contains(key)) {
            rejectDeterministic(impact, "mock_stub", stubId,
                    "上游契约新版本已不再包含该桩对应的接口（" + key + "），没有可更新的草案");
            return;
        }
        String payload = writeJson(Map.of(
                "name", String.valueOf(stub.get("name")),
                "method", String.valueOf(stub.get("method")),
                "pathPattern", String.valueOf(stub.get("path_pattern"))));
        proposalService.record(new LinkageProposalInput(impact.id(), "mock_stub", stubId,
                payload, DETERMINISTIC_SCHEMA_VERSION, DETERMINISTIC_MODEL_NAME,
                ((Number) stub.get("version")).intValue(), null));
    }

    private void recordRequestSnapshot(LinkageImpactInfo impact, long requestId,
                                       List<String> armedKeys) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.contract_endpoint_key, r.collection_id, c.headers_json::text AS headers_json"
                        + " FROM tp_apitest_request r"
                        + " JOIN tp_apitest_collection c ON c.id = r.collection_id WHERE r.id = ?",
                requestId);
        if (rows.isEmpty()) {
            rejectDeterministic(impact, "apitest_request", requestId,
                    "该引入请求行已被删除（请求 #" + requestId + "），上游契约新版本没有可更新的请求");
            return;
        }
        Map<String, Object> request = rows.get(0);
        String looseKey = request.get("contract_endpoint_key") == null
                ? "" : String.valueOf(request.get("contract_endpoint_key"));
        if (looseKey.isBlank() || !armedKeys.contains(matchKeyRules.matchKeyFromLoose(looseKey))) {
            rejectDeterministic(impact, "apitest_request", requestId,
                    "上游契约新版本已不再包含该引入请求对应的接口（" + looseKey + "），没有可更新的草案");
            return;
        }
        Object headers = request.get("headers_json");
        String payload = writeJson(Map.of(
                "collectionId", ((Number) request.get("collection_id")).longValue(),
                "headersJson", headers == null ? "[]" : String.valueOf(headers)));
        proposalService.record(new LinkageProposalInput(impact.id(), "apitest_request", requestId,
                payload, DETERMINISTIC_SCHEMA_VERSION, DETERMINISTIC_MODEL_NAME, null, null));
    }

    private void rejectDeterministic(LinkageImpactInfo impact, String type, long downstreamId,
                                     String reason) {
        proposalService.record(new LinkageProposalInput(impact.id(), type, downstreamId,
                null, null, null, null, reason));
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("deterministic contract snapshot is unserialisable",
                    ex);
        }
    }

    /** Bus token of the announcement ({@code case} for {@code CASE}) — ledger column shape. */
    private static String objectTypeToken(String eventType) {
        return com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType
                .valueOf(eventType).objectType();
    }
}
