package com.sixbean.testpike.core.session.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskInfo;
import com.sixbean.testpike.core.executor.domain.component.SessionStateDeclaration;
import com.sixbean.testpike.core.executor.domain.component.SessionStateDeclarationSupport;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import com.sixbean.testpike.core.session.infrastructure.dataobject.LoginState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Auth-rejection writeback for consumed login states (AV face 3). A browser task
 * (task_type=1) whose action JSON declares a sessionState consumed a ledger row;
 * if the relayed evidence then carries an UNEXPECTED HTTP 401/403, that state is
 * no longer valid and the ledger light must turn off immediately — instead of
 * waiting for a scheduled probe, every later run would keep failing on it.
 * <p>Single-source discipline (red line 6): the only write path is
 * LoginStateService.markVerified; the only declaration parser is
 * SessionStateDeclarationSupport; the only resolution is SessionStateSupport.
 * This component copies none of them. The invalid row is picked up by the
 * existing batch start gate and the claim refusal next round (closed fuse loop).
 * <p>Negative-case exemption mirrors the executor api_runner C1 heuristic: a run
 * where the assertion explicitly expects 401/403 is an intended outcome, not a
 * rejection, and never writes back.
 * <p>Hook contract: called at the tail of ExecTaskServiceImpl.submitResult after
 * the terminal UPDATE, in the same transaction. submitResult's idempotent early
 * return on non-RUNNING tasks protects this hook from duplicate rewrites, and any
 * failure here is logged and swallowed — a writeback problem must never break
 * the result relay chain.
 */
@Component
public class SessionAuthRejectionWriteback {

    private static final Logger log = LoggerFactory.getLogger(SessionAuthRejectionWriteback.class);

    /** HTTP codes that mean "the consumed login state was rejected". */
    private static final List<Integer> REJECTION_CODES = List.of(401, 403);

    private final SessionStateDeclarationSupport declarationSupport;
    private final SessionStateSupport sessionStateSupport;
    private final LoginStateService loginStateService;
    private final ObjectMapper objectMapper;

    public SessionAuthRejectionWriteback(SessionStateDeclarationSupport declarationSupport,
                                         SessionStateSupport sessionStateSupport,
                                         LoginStateService loginStateService,
                                         ObjectMapper objectMapper) {
        this.declarationSupport = declarationSupport;
        this.sessionStateSupport = sessionStateSupport;
        this.loginStateService = loginStateService;
        this.objectMapper = objectMapper;
    }

    /** Result-relay tail hook: see class contract. Never throws. */
    public void onResultSubmitted(ExecTaskInfo task, ExecResultInput input) {
        if (task.taskType() != 1) {
            return;
        }
        try {
            SessionStateDeclaration declaration =
                    declarationSupport.declaration(task.actionJson());
            if (declaration == null) {
                return;
            }
            LoginState row;
            try {
                row = sessionStateSupport.resolve(task.projectId(),
                        declaration.name(), declaration.roleScope());
            } catch (ServiceException unresolvable) {
                // The ledger row vanished or was disabled mid-flight: nothing to
                // write back to — honest skip, the terminal state stays as saved.
                return;
            }
            Integer rejected = findUnexpectedRejection(input, row);
            if (rejected == null) {
                return;
            }
            loginStateService.markVerified(row.getId(), false,
                    "执行用例时该登录态被被测拒绝（HTTP " + rejected
                            + "），已判定失效；请在登录态账本重新采集后再启用。");
        } catch (RuntimeException hookFailure) {
            log.warn("login-state auth-rejection writeback skipped for task {}: {}",
                    task.id(), hookFailure.getMessage());
        }
    }

    /**
     * First rejection code visible to core on the evidence planes:
     * http.request protocol snapshots (response.status) and net.assert hits
     * (netHits[].status) and the browser-navigation relay plane
     * (sessionAuthRejections). Codes an assertion explicitly expects are exempt.
     * Status values arrive as Number or String depending on the relay path,
     * so both forms are tolerated here.
     */
    private Integer findUnexpectedRejection(ExecResultInput input, LoginState row) {
        List<Map<String, Object>> snapshots = mapRows(input.protocolSnapshots());
        for (Map<String, Object> snapshot : snapshots) {
            if (!"http.request".equals(snapshot.get("kind"))) {
                continue;
            }
            Integer status = statusOf(mapRowsValue(snapshot.get("response")).get("status"));
            if (status != null && REJECTION_CODES.contains(status)
                    && !expectsStatus(assertionRows(snapshot), status)) {
                return status;
            }
        }
        for (Map<String, Object> hit : mapRows(input.netHits())) {
            Integer status = statusOf(hit.get("status"));
            if (status != null && REJECTION_CODES.contains(status)
                    && !expectedByAnySnapshot(snapshots, status)) {
                return status;
            }
        }
        // Browser-navigation plane (34b): rows the executor relayed verbatim
        // after narrowing to the run's own environment origin. The ledger's
        // scope_origins whitelist stays the single protected-surface authority;
        // the expected-status exemption applies here too.
        for (Map<String, Object> observed : mapRows(input.sessionAuthRejections())) {
            Integer status = statusOf(observed.get("status"));
            if (status != null && REJECTION_CODES.contains(status)
                    && !expectedByAnySnapshot(snapshots, status)
                    && withinLedgerScope(observed.get("url"), row)) {
                return status;
            }
        }
        return null;
    }

    /**
 * Origin-whitelist check for the navigation plane: a rejection from an
 * origin the state never covers says nothing about this state and must
 * not invalidate it. A row without a scope list accepts the observation —
 * the executor already narrowed it to the run's environment origin and
 * in the executor is exactly the drift red line 6 forbids).
 */
    private static boolean withinLedgerScope(Object rawUrl, LoginState row) {
        String origin = originOf(rawUrl == null ? "" : String.valueOf(rawUrl));
        if (origin == null) {
            return false;
        }
        List<String> scope = row.originsAsList();
        if (scope == null || scope.isEmpty()) {
            return true;
        }
        return scope.stream()
                .anyMatch(entry -> origin.equals(originOf(String.valueOf(entry))));
    }

    /** Lowercased scheme://host[:port] of a URL or scope entry; null when unusable. */
    private static String originOf(String url) {
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            String origin = uri.getScheme().toLowerCase()
                    + "://" + uri.getHost().toLowerCase();
            return uri.getPort() > 0 ? origin + ":" + uri.getPort() : origin;
        } catch (Exception malformed) {
            return null;
        }
    }

    private List<Map<String, Object>> assertionRows(Map<String, Object> snapshot) {
        return mapRows(snapshot.get("assertions"));
    }

    private static boolean expectsStatus(List<Map<String, Object>> rows, int status) {
        return rows.stream().anyMatch(row -> "status".equals(row.get("type"))
                && statusOf(row.get("expect")) != null && statusOf(row.get("expect")) == status);
    }

    private boolean expectedByAnySnapshot(List<Map<String, Object>> snapshots, int status) {
        return snapshots.stream().anyMatch(s -> expectsStatus(assertionRows(s), status));
    }

    private static Integer statusOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException notNumeric) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapRowsValue(Object node) {
        if (node instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    /** Evidence lists may arrive as List, JSON string or null across relay paths. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapRows(Object node) {
        if (node instanceof List<?> list) {
            return list.stream().filter(Map.class::isInstance)
                    .map(entry -> (Map<String, Object>) entry).toList();
        }
        if (node instanceof String json && !json.isBlank()) {
            try {
                return mapRows(objectMapper.readValue(json, List.class));
            } catch (Exception malformed) {
                return List.of();
            }
        }
        return List.of();
    }
}
