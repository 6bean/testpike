package com.sixbean.testpike.admin.deadletter.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterBrowsePageView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterClassCountView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterLedgerRowView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterQueueCountView;
import com.sixbean.testpike.admin.deadletter.api.dto.DeadLetterReplayReceiptView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * core dead-letter ledger client (AJ batch ; request chain admin→core
 * /internal/mq-dead-letter/** — core never faces users directly).
 * <p>Filter values are URL-encoded here and nothing else: defaulting, whitelist
 * checks and fail-closed messages belong to the core query service (single source).
 */
@Component
public class RestDeadLetterCoreClient {

    private final RestClient rest;

    public RestDeadLetterCoreClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    /** Per-queue grouping for one status (null = core default = pending backlog). */
    public List<DeadLetterQueueCountView> queueBacklog(String status) {
        return call(HttpMethod.GET, append("/internal/mq-dead-letter/queue-backlog", "status", status),
                null, new ParameterizedTypeReference<>() {
                });
    }

    /** Failure-class histogram within an optional queue scope. */
    public List<DeadLetterClassCountView> classDistribution(String queueName, String status) {
        String uri = append(append("/internal/mq-dead-letter/class-distribution",
                "queueName", queueName), "status", status);
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    /** Paged browsing row (list+total straight from the ledger query). */
    public DeadLetterBrowsePageView browse(String queueName, String failureClass, String status,
                                           int page, int size) {
        String uri = append(append(append(
                "/internal/mq-dead-letter/browse?page=" + page + "&size=" + size,
                "queueName", queueName), "failureClass", failureClass), "status", status);
        return call(HttpMethod.GET, uri, null, new ParameterizedTypeReference<>() {
        });
    }

    private String append(String uri, String key, String value) {
        if (key == null || value == null || value.isBlank()) {
            return uri;
        }
        return uri + (uri.contains("?") ? "&" : "?") + key + "="
                + URLEncoder.encode(value.trim(), StandardCharsets.UTF_8);
    }

    /** Replay one row; core runs the guards, the operator rides X-Operator-Id. */
    public DeadLetterReplayReceiptView replayOne(long ledgerId, boolean confirmReexecute) {
        return call(HttpMethod.POST, "/internal/mq-dead-letter/replay/one?ledgerId=" + ledgerId
                + "&confirmReexecute=" + confirmReexecute, null, new ParameterizedTypeReference<>() {
        });
    }

    /** Batch replay of one failure class inside one DLQ; returns the reposted count. */
    public int replayByClass(String queueName, String failureClass, boolean confirmReexecute) {
        String uri = append(append(append("/internal/mq-dead-letter/replay/by-class",
                "queueName", queueName), "failureClass", failureClass),
                "confirmReexecute", String.valueOf(confirmReexecute));
        Integer reposted = call(HttpMethod.POST, uri, null, new ParameterizedTypeReference<>() {
        });
        return reposted == null ? 0 : reposted;
    }

    /** Whole-queue replay of one DLQ (ENTITY_GONE rows stay pending); returns the reposted count. */
    public int replayQueue(String queueName, boolean confirmReexecute) {
        String uri = append(append("/internal/mq-dead-letter/replay/queue",
                "queueName", queueName), "confirmReexecute", String.valueOf(confirmReexecute));
        Integer reposted = call(HttpMethod.POST, uri, null, new ParameterizedTypeReference<>() {
        });
        return reposted == null ? 0 : reposted;
    }

    /** Void one pending row with a recorded reason (never deleted). */
    public boolean voidLedger(long ledgerId, String reason) {
        String uri = "/internal/mq-dead-letter/void?ledgerId=" + ledgerId
                + "&reason=" + URLEncoder.encode(reason == null ? "" : reason.trim(), StandardCharsets.UTF_8);
        Boolean flipped = call(HttpMethod.POST, uri, null, new ParameterizedTypeReference<>() {
        });
        return Boolean.TRUE.equals(flipped);
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
